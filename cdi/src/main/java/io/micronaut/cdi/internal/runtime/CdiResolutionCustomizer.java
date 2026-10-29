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

import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.BeanResolutionCustomizer;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.QualifiedBeanType;
import io.micronaut.inject.provider.JakartaProviderBeanDefinition;
import jakarta.enterprise.inject.IllegalProductException;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Micronaut's resolution, adjusted to the type rules of section 2.4.2.1 where the two disagree.
 *
 * <p>Micronaut's own candidate test answers most lookups; what it does not know are the specification's rules
 * for a bean whose type carries type variables — a generic dependent bean is a candidate for whatever fits its
 * bounds, and a raw producer for a required type that says nothing. When Micronaut says no to a lookup with
 * type arguments, the specification's own matching gets the last word. Arrays also use that matching,
 * including raw array lookups, since their element types must be identical.</p>
 *
 * <p>The injection points of a bean of the specification are not resolved here: the container selects their beans
 * itself ({@link CdiBeanInjectionProvider}). What this adjusts are the lookups Micronaut makes on its own - the
 * points of a bean of Micronaut's own, a program's {@code BeanContext.getBean} - and the creation of what the
 * container selected. Only the beans of the specification are matched by its rules: a candidate of Micronaut's
 * own is left to the customizer this one was installed over, Micronaut's default where there was none, so that
 * adding this module to an application leaves the resolution of its own beans as it was.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiResolutionCustomizer implements BeanResolutionCustomizer {

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
        boolean.class, false,
        byte.class, (byte) 0,
        short.class, (short) 0,
        int.class, 0,
        long.class, 0L,
        float.class, 0f,
        double.class, 0d,
        char.class, '\u0000');
    private final BeanResolutionCustomizer delegate;
    // the context this customizer serves, which Micronaut hands it before resolving anything (forContext); none for
    // the one installed on the builder, which only hands out the customizer of each context it builds
    private final @Nullable BeanContext beanContext;
    // the generic hierarchies of that context, read from it as the first parameterized lookup is made
    private volatile @Nullable RecordedTypeIndex typeIndex;

    /**
     * @param delegate The customizer installed before this one, which resolves what is not the specification's
     */
    CdiResolutionCustomizer(BeanResolutionCustomizer delegate) {
        this(delegate, null);
    }

    private CdiResolutionCustomizer(BeanResolutionCustomizer delegate,
                                    @Nullable BeanContext beanContext) {
        this.delegate = delegate;
        this.beanContext = beanContext;
    }

    /**
     * The customizer of one context, which holds the context and the bean types it worked out for it. One builder
     * may build several contexts, and a context that reads a bootstrap environment builds another from its
     * configuration: each is served by a customizer of its own, over the one the customizer installed before this
     * one hands that context.
     *
     * @param beanContext The context, not yet resolving anything
     * @return The customizer that serves it
     */
    @Override
    public BeanResolutionCustomizer forContext(BeanContext beanContext) {
        return new CdiResolutionCustomizer(delegate.forContext(beanContext), beanContext);
    }

    /**
     * The generic hierarchies of the context this customizer serves. They are beans of the context, and a
     * parameterized lookup is made once the context is resolving beans, so they are read from it the first time
     * one is: from the first lookup on, it is matched by them.
     */
    private @Nullable RecordedTypeIndex typeIndex() {
        RecordedTypeIndex index = typeIndex;
        if (index == null && beanContext != null) {
            index = RecordedTypeIndex.of(beanContext);
            typeIndex = index;
        }
        return index;
    }

    /**
     * The default value of a primitive type, which a producer returning null satisfies a point of it with.
     *
     * @param type The primitive type
     * @return The default value, or {@code null} for a type that is not primitive
     */
    static @Nullable Object primitiveDefaultOf(Class<?> type) {
        return PRIMITIVE_DEFAULTS.get(type);
    }

    @Override
    public Argument<?> resolveBeanLookupArgument(Argument<?> beanType) {
        // section 2.1.2 counts a primitive and the class that boxes it as one bean type; Micronaut keeps them
        // apart, so a primitive lookup is made as the boxed one
        Class<?> raw = beanType.getType();
        if (raw.isPrimitive() && raw != void.class) {
            Class<?> boxed = CdiTypes.boxedOf(raw);
            return Argument.of(boxed, beanType.getAnnotationMetadata(), (Class<?>[]) null);
        }
        return delegate.resolveBeanLookupArgument(beanType);
    }

    @Override
    public boolean shouldResolveArrayAsBean(Argument<?> injectionPoint) {
        // an array is a bean type of its own in the specification, but an injection point of a bean of the
        // specification is marked as it compiles to be resolved by the container (ContainerInjectionVisitor): one
        // reaching Micronaut's own collecting is a bean of Micronaut's
        return delegate.shouldResolveArrayAsBean(injectionPoint);
    }

    @Override
    public <T> Optional<BeanDefinition<T>> resolveNonUniqueBean(
        Argument<T> beanType,
        @Nullable Qualifier<T> qualifier,
        Collection<BeanDefinition<T>> candidates) {
        if (beanType.getType() == Provider.class && candidates.size() == 2
            && candidates.stream().anyMatch(candidate ->
                candidate instanceof JakartaProviderBeanDefinition)) {
            // Instance extends Provider. Its built-in factory captures the injection point and owns the
            // dependent instances it creates; Micronaut's ordinary Provider does not retain that metadata.
            // Resolve only this overlap between the two built-ins, leaving user-defined providers alone.
            for (BeanDefinition<T> candidate : candidates) {
                if (candidate instanceof CdiInstanceFactory<?>) {
                    return Optional.of(candidate);
                }
            }
        }
        BeanDefinition<T> specification = ofTheSpecification(qualifier, candidates);
        if (specification != null) {
            return Optional.of(specification);
        }
        return delegate.resolveNonUniqueBean(beanType, qualifier, candidates);
    }

    /**
     * The one bean the specification resolves among the candidates of a lookup Micronaut makes - a point of a bean of
     * Micronaut's own, a program's {@code BeanContext.getBean} - where beans of the specification are among them and
     * no primary bean decided: the beans of the specification outrank the beans of Micronaut's own, a lookup that
     * names no qualifier looks for the default one (section 3.8), and the selected alternatives of the highest
     * priority outrank the rest (section 2.4.2). That the bean is not declared Micronaut's primary leaves an
     * application's own primary bean deciding, as it does without this module.
     *
     * @return The bean, or {@code null} where the rules do not pick one, or no bean of the specification is a candidate
     */
    private static <T> @Nullable BeanDefinition<T> ofTheSpecification(
        @Nullable Qualifier<T> qualifier,
        Collection<BeanDefinition<T>> candidates) {
        List<BeanDefinition<T>> eligible = new ArrayList<>(candidates.size());
        for (BeanDefinition<T> candidate : candidates) {
            if (CdiResolution.isBeanOfTheSpecification(candidate)
                && (qualifier != null || CdiAssignability.areQualifiersMatching(
                    CdiQualifier.declared(candidate.getAnnotationMetadata()), List.of()))) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        List<BeanDefinition<T>> narrowed = CdiResolution.narrow(eligible);
        return narrowed.size() == 1 ? narrowed.get(0) : null;
    }

    @Override
    public Optional<?> resolveNullBean(Argument<?> requestedBeanType, Argument<?> resolvedBeanType,
                                       BeanDefinition<?> beanDefinition) {
        // section 2.5.2.4: a producer that returns null satisfies a primitive injection point with the
        // primitive's default value
        Object primitiveDefault = PRIMITIVE_DEFAULTS.get(requestedBeanType.getType());
        if (primitiveDefault != null) {
            return Optional.of(primitiveDefault);
        }
        AnnotationMetadata metadata = beanDefinition.getAnnotationMetadata();
        if (metadata.hasAnnotation(CdiProducer.class)
            && (CdiResolution.isNormalScoped(metadata)
                || beanDefinition.isSingleton())) {
            // section 3.2.2: an instance in any scope but dependent is shared, and a null cannot be
            throw new IllegalProductException(
                "The producer of " + beanDefinition.getBeanType().getName() + " returned null, which only a "
                    + "producer of a dependent instance may");
        }
        return delegate.resolveNullBean(requestedBeanType, resolvedBeanType, beanDefinition);
    }

    @Override
    public boolean shouldInitializeBean(BeanResolutionContext resolutionContext,
                                        BeanDefinition<?> beanDefinition, Object bean) {
        // a client proxy delegates every call to the instance its context holds: injecting the proxy's own
        // fields would chase the dependencies the proxy exists to defer, which is how a circular chain of
        // normal-scoped beans deadlocks — and section 4.3 has the proxy break exactly that circle
        if (beanDefinition instanceof ProxyBeanDefinition<?>
            && CdiResolution.isNormalScoped(beanDefinition.getAnnotationMetadata())) {
            return false;
        }
        return delegate.shouldInitializeBean(resolutionContext, beanDefinition, bean);
    }

    @Override
    public boolean shouldPreserveLazyProxyTargetResolutionPath(
        BeanResolutionContext resolutionContext, BeanDefinition<?> proxyBeanDefinition) {
        // when a client proxy finally resolves the instance its context holds, that resolution starts a fresh
        // dependency chain: carrying the path the proxy was injected on would read a legal circular chain of
        // normal-scoped beans — the one section 4.3 has the proxy break — as a circular dependency
        if (CdiResolution.isNormalScoped(proxyBeanDefinition.getAnnotationMetadata())) {
            return false;
        }
        return delegate.shouldPreserveLazyProxyTargetResolutionPath(resolutionContext, proxyBeanDefinition);
    }

    @Override
    public boolean isCandidateBean(Argument<?> beanType, QualifiedBeanType<?> candidate) {
        if ((beanType.getTypeParameters().length > 0 || CdiTypes.isArray(beanType))
            && candidate instanceof BeanDefinition<?> definition
            && CdiResolution.isBeanOfTheSpecification(definition)) {
            // a parameterized lookup of a bean of the specification is answered by the rules of section
            // 2.4.2.1 in both directions: they admit what Micronaut's own matching would not — a variable
            // matched by its bounds — and refuse what it would let through — a variable whose other bounds
            // the required argument does not satisfy. The injection points of a bean of the specification are
            // selected by the container; what reaches this is a lookup Micronaut makes itself - a point of a bean
            // of Micronaut's own, BeanContext.getBean - and the creation of a definition the container selected,
            // which Micronaut checks against the type it is asked as
            RecordedTypeIndex index = typeIndex();
            try {
                // the bean types the container compares too, worked out once for the context
                List<Argument<?>> types = index != null ? index.beanTypesOf(definition)
                    : CdiBean.typesOf(null, definition, RecordedTypeIndex.beanClassOf(definition));
                return CdiAssignability.isTypeMatching(index, types, beanType);
            } catch (NoClassDefFoundError | TypeNotPresentException e) {
                // a type the bean recorded is of a class absent from the classpath: what Micronaut matches it by
                // is all there is to go on
                return delegate.isCandidateBean(beanType, candidate);
            }
        }
        return delegate.isCandidateBean(beanType, candidate);
    }
}
