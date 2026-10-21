/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.extension.ExtensionComponents;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.QualifiedBeanType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The rules of section 2.4.2 as a Micronaut qualifier: a bean qualifies for a lookup when one of its bean types
 * matches the required type (section 2.4.2.1) and it has every qualifier the lookup requires, with what a portable
 * extension added to its class as the container started.
 *
 * <p>Micronaut finds the candidates, the qualifier tells which of them the specification admits. The bean context
 * is asked for the definitions of the {@linkplain #lookupType() raw class} of the required type: its own matching
 * of type arguments is not the one of the specification - a variable matched by its bounds, a raw bean type, a
 * wildcard - so it is not asked to make it, and the type of the specification is compared here instead. What the
 * bean context does on the way is what it does for every lookup: the definitions that are disabled are not there,
 * neither are the targets of proxies, and a definition another one replaces with {@code Replaces} is left out
 * before this qualifier sees them.</p>
 *
 * <p>Nothing is kept: the qualifiers of a bean are read as the lookup is made, so a qualifier an extension adds
 * after an earlier lookup is seen by the next one, and the bean context keeps the candidates of a type and never
 * the result of a qualifier ({@code BeanContext.getBeanDefinitions(Argument, Qualifier)} filters on each call).
 * The qualifier is never handed to the creation of a bean: a qualifier there is part of the key a scope holds the
 * instance under.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class CdiMatching<T> implements Qualifier<T> {

    private final CdiBeanContainer container;
    private final Argument<?> required;
    private final List<CdiQualifier> qualifiers;

    private CdiMatching(CdiBeanContainer container, Argument<?> required, List<CdiQualifier> qualifiers) {
        this.container = container;
        this.required = required;
        this.qualifiers = List.copyOf(qualifiers);
    }

    /**
     * The qualifier of a lookup.
     *
     * @param container  The container whose beans are compared
     * @param required   The required type, with no type variable in it
     * @param qualifiers The required qualifiers, none at all requiring the default qualifier
     * @param <T>        The bean type
     * @return The qualifier
     */
    static <T> CdiMatching<T> of(CdiBeanContainer container, Argument<?> required, List<CdiQualifier> qualifiers) {
        return new CdiMatching<>(container, required, qualifiers);
    }

    /**
     * The type the bean context is asked for the candidates of: the raw class of the required type, which every
     * bean of a matching type is indexed by, so that Micronaut's own matching of type arguments rejects nothing the
     * specification would admit. An array is asked for as it is: an array type matches only an identical one, which
     * is the rule the resolution customizer applies to it on both sides. A type that names no class is asked for as
     * {@code Object}, which is every bean.
     *
     * @return The type to look the candidates up by
     */
    @SuppressWarnings("unchecked")
    Argument<T> lookupType() {
        if (CdiTypes.isArray(required)) {
            return (Argument<T>) required;
        }
        Class<?> raw = CdiTypes.classOf(required);
        if (raw == null || raw == Object.class) {
            return (Argument<T>) Argument.OBJECT_ARGUMENT;
        }
        // section 2.1.2 counts a primitive and the class that boxes it as one bean type: asked for as the
        // primitive, the context also enumerates the beans of the boxed class (resolveBeanLookupArgument of the
        // resolution customizer), and the other way around it would not
        Class<?> primitive = io.micronaut.core.reflect.ReflectionUtils.getPrimitiveType(raw);
        return (Argument<T>) Argument.of(primitive, LOOKUP);
    }

    /**
     * The name the type the container asks the bean context for candidates by is given, which tells the resolution
     * customizer that the lookup is one of the container's, whose candidates are every bean that exposes the class:
     * Micronaut's own test of a raw lookup has a bean of a container type - a producer of a {@code List} - stand for
     * its elements and refuses it to a lookup of a supertype of its class, {@code Collection} say, which the type of
     * the specification may well be. As part of the type, the name also keeps the candidates the bean context keeps
     * for such a lookup apart from the ones it keeps for a lookup of its own of the class.
     */
    static final String LOOKUP = "$cdiLookup";

    /**
     * Whether the type a lookup is made by is one the container asked for the candidates of a type of the
     * specification by ({@link #lookupType()}).
     *
     * @param type The type
     * @return Whether it is the container's
     */
    static boolean isLookupOfTheContainer(Argument<?> type) {
        return LOOKUP.equals(type.getName());
    }

    /**
     * The required type.
     *
     * @return The type
     */
    Argument<?> required() {
        return required;
    }

    /**
     * The required qualifiers.
     *
     * @return The qualifiers
     */
    List<CdiQualifier> qualifiers() {
        return qualifiers;
    }

    @Override
    public <B extends BeanType<T>> Stream<B> reduce(Class<T> beanType, Stream<B> candidates) {
        return candidates.filter(this::matches);
    }

    @Override
    public <B extends BeanType<T>> Collection<B> filter(Class<T> beanType, Collection<B> candidates) {
        List<B> kept = new ArrayList<>(candidates.size());
        for (B candidate : candidates) {
            if (matches(candidate)) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    @Override
    public <B extends QualifiedBeanType<T>> Collection<B> filterQualified(Class<T> beanType, Collection<B> candidates) {
        return filter(beanType, candidates);
    }

    @Override
    public boolean doesQualify(Class<T> beanType, BeanType<T> candidate) {
        return matches(candidate);
    }

    private boolean matches(BeanType<?> candidate) {
        if (!(candidate instanceof BeanDefinition<?> definition) || !isInjectable(definition)) {
            return false;
        }
        if (definition instanceof CdiInjectionPointFactory<?> builtIn) {
            // the built-in event and lookup exist for whatever legal type an injection point asks them for, so
            // the one bean of each answers every parameterization of its type, the raw type itself, and every
            // qualifier
            Class<?> raw = CdiTypes.classOf(required);
            return raw != null && builtIn.isBeanType(raw);
        }
        CdiBean<?> bean = container.canonicalBean(definition);
        return CdiAssignability.isTypeMatching(container.typeIndex(), bean.types(), required)
            && CdiAssignability.areQualifiersMatching(bean.qualifiers(), qualifiers);
    }

    /**
     * Whether a definition the bean context enumerates is a bean of the container: not the definition of an
     * abstract class, which section 3.1.1 says no bean is, however it is annotated, and not one of the components
     * an extension had the container instantiate - a creator, a disposer, a synthetic observer, a context.
     *
     * @param definition The definition
     * @return Whether it is a bean
     */
    static boolean isBean(BeanDefinition<?> definition) {
        return !definition.isAbstract() && !ExtensionComponents.isComponent(definition.getAnnotationMetadata());
    }

    /**
     * Whether a definition is a bean a lookup or an injection point may resolve to: a {@linkplain #isBean bean}
     * that is not an interceptor, which is never injected.
     *
     * @param definition The definition
     * @return Whether it may be injected
     */
    static boolean isInjectable(BeanDefinition<?> definition) {
        return isBean(definition) && !isInterceptor(definition);
    }

    /**
     * Whether the definition is an interceptor's.
     *
     * @param definition The definition
     * @return Whether it is one
     */
    static boolean isInterceptor(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor");
    }

    /**
     * Whether every bean this qualifier selects the other one selects too: the same required type, and at least
     * every qualifier the other requires.
     *
     * @param qualifier The other qualifier
     * @return Whether this one contains it
     */
    @Override
    public boolean contains(Qualifier<T> qualifier) {
        if (!(qualifier instanceof CdiMatching<T> other) || !required.equalsStructure(other.required)) {
            return false;
        }
        for (CdiQualifier one : other.qualifiers) {
            if (!one.isAmong(qualifiers)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CdiMatching<?> other) || container != other.container
            || qualifiers.size() != other.qualifiers.size() || !required.equalsStructure(other.required)) {
            return false;
        }
        // the order the qualifiers were named in makes no difference to the beans selected
        for (CdiQualifier one : qualifiers) {
            if (!one.isAmong(other.qualifiers)) {
                return false;
            }
        }
        for (CdiQualifier one : other.qualifiers) {
            if (!one.isAmong(qualifiers)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // over the names of the qualifiers alone, as a qualifier's own hash is: two qualifiers that match are of
        // one type, while which members bind is only settled once both sides are compared
        Set<String> names = new HashSet<>();
        for (CdiQualifier qualifier : qualifiers) {
            names.add(qualifier.name());
        }
        return required.structureHashCode() * 31 + names.hashCode();
    }

    @Override
    public String toString() {
        return required.getTypeString(true) + " "
            + (qualifiers.isEmpty() ? "@jakarta.enterprise.inject.Default" : qualifiers.toString());
    }
}
