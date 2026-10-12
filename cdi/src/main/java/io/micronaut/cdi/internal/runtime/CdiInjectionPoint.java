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

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.cdi.spi.InjectionSite;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One injection point of a bean, described from what was compiled.
 *
 * <p>The type and the qualifiers come from the argument Micronaut resolved for the point, generics included. The
 * member and its annotated model are the parts the specification asks for that compiled metadata does not carry
 * as objects: they are reflection objects, handed out through {@link CdiReflection} when a program asks for
 * them, and not read anywhere a bean is resolved or injected. Which member and which parameter of it the point
 * is, is what the compiled metadata does carry - the {@link InjectionSite} - so that the reflection module finds
 * exactly that member, an overload of one name and a parameter of one type among others included.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInjectionPoint implements io.micronaut.cdi.MicronautInjectionPoint {

    private final @Nullable Bean<?> bean;
    private final Argument<?> argument;
    private final @Nullable InjectionSite site;
    private final java.util.List<CdiQualifier> selectedQualifiers;

    CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, @Nullable InjectionSite site) {
        this(bean, argument, site, java.util.List.of());
    }

    private CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, @Nullable InjectionSite site,
                              java.util.List<CdiQualifier> selectedQualifiers) {
        this.selectedQualifiers = selectedQualifiers;
        this.bean = bean;
        this.argument = argument;
        this.site = site;
    }

    /**
     * The site of one parameter of a constructor or method, from the arguments Micronaut compiled for it: the
     * parameters Micronaut generates for an intercepted bean are not the author's, and are left out.
     *
     * @param declaringClass The class that declares the constructor or method
     * @param memberName     The name of the method, {@value InjectionSite#CONSTRUCTOR} for a constructor
     * @param arguments      The arguments of the constructor or method
     * @param argument       The injected one among them
     * @return The site
     */
    static InjectionSite parameterSite(Class<?> declaringClass, String memberName, Argument<?>[] arguments,
                                       Argument<?> argument) {
        List<Class<?>> types = new ArrayList<>(arguments.length);
        int index = -1;
        for (Argument<?> parameter : arguments) {
            if (CdiResolution.isGeneratedConstructorParameter(parameter)) {
                continue;
            }
            // the names of the parameters of one executable are distinct, which tells an argument apart from
            // another of the same type
            if (index < 0 && parameter.getName().equals(argument.getName())) {
                index = types.size();
            }
            types.add(parameter.getType());
        }
        return InjectionSite.ofParameter(declaringClass, memberName, types, index);
    }

    @Override
    public Type getType() {
        return CdiTypes.typeOf(argument);
    }

    @Override
    public Set<Annotation> getQualifiers() {
        // the qualifiers of an injection point are the ones written on it, and a point that writes none has
        // the default qualifier — with nothing else added
        Set<Annotation> declared = CdiQualifiers.declared(argument.getAnnotationMetadata());
        if (!selectedQualifiers.isEmpty()) {
            // an Instance narrowed with select has the qualifiers it was selected with as well (section 5.6.1)
            Set<Annotation> all = new java.util.LinkedHashSet<>(declared);
            all.addAll(CdiQualifier.instances(selectedQualifiers));
            return java.util.Collections.unmodifiableSet(all);
        }
        return declared.isEmpty()
            ? Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE)
            : declared;
    }

    @Override
    public java.util.List<io.micronaut.core.annotation.AnnotationValue<?>> getQualifierValues() {
        java.util.List<io.micronaut.core.annotation.AnnotationValue<?>> all = new java.util.ArrayList<>();
        for (CdiQualifier declared : CdiQualifier.declared(argument.getAnnotationMetadata())) {
            all.add(declared.written());
        }
        for (CdiQualifier selected : selectedQualifiers) {
            all.add(selected.written());
        }
        if (all.isEmpty()) {
            all.add(CdiQualifier.DEFAULT.written());
        }
        return java.util.Collections.unmodifiableList(all);
    }

    @Override
    public @Nullable Bean<?> getBean() {
        return bean;
    }

    @Override
    public @Nullable Member getMember() {
        InjectionSite site = this.site;
        if (site == null) {
            // an injection point that stands for a programmatic lookup has no member
            return null;
        }
        return CdiReflection.current("InjectionPoint.getMember()").member(site);
    }

    /**
     * This injection point seen with the type a lookup selected: a bean obtained through {@code Instance} has
     * the lookup's injection point as its metadata, with the type it was selected as.
     *
     * @param selected   The selected type
     * @param qualifiers The qualifiers the lookup was selected with
     * @return The injection point, typed as selected
     */
    CdiInjectionPoint viewedAs(Argument<?> selected, java.util.List<CdiQualifier> qualifiers) {
        Argument<?> viewed = Argument.of(selected.getType(), argument.getAnnotationMetadata(),
            selected.getTypeParameters());
        // the member is still the one that was written, whatever the lookup was selected as
        return new CdiInjectionPoint(bean, viewed, site, qualifiers);
    }

    /**
     * Describes the injection point a resolution segment stands for: the argument being injected, into the
     * member of the bean the segment declares.
     *
     * @param bean    The bean declaring the injection point
     * @param segment The segment
     * @return The injection point
     */
    public static CdiInjectionPoint of(Bean<?> bean, io.micronaut.context.BeanResolutionContext.Segment<?, ?> segment) {
        Class<?> declaring = bean.getBeanClass();
        Argument<?> argument = segment.getArgument();
        InjectionSite site;
        if (segment instanceof io.micronaut.inject.FieldInjectionPoint<?, ?> field) {
            site = InjectionSite.ofField(declaring, field.getName());
        } else if (segment instanceof io.micronaut.inject.ArgumentInjectionPoint<?, ?> parameter
            && parameter.getOuterInjectionPoint() instanceof io.micronaut.inject.CallableInjectionPoint<?> outer) {
            // the factory method of a produced bean is the constructor of that bean to Micronaut, and a method
            // all the same, whose name is the member's
            String member = outer instanceof io.micronaut.inject.MethodInjectionPoint<?, ?> method
                ? method.getName() : InjectionSite.CONSTRUCTOR;
            site = parameterSite(declaring, member, outer.getArguments(), argument);
        } else {
            site = InjectionSite.ofParameter(declaring, segment.getName(), List.of(), -1);
        }
        return new CdiInjectionPoint(bean, argument, site);
    }

    @Override
    public Annotated getAnnotated() {
        InjectionSite site = this.site;
        if (site == null) {
            throw new UnsupportedOperationException("An injection point that stands for a programmatic lookup "
                + "has no annotated model");
        }
        return CdiReflection.current("InjectionPoint.getAnnotated()").annotated(site);
    }

    @Override
    public boolean isDelegate() {
        return false;
    }

    @Override
    public boolean isTransient() {
        // only a field can be transient, and whether one is is read off the field itself
        return site != null && site.field() && getMember() instanceof java.lang.reflect.Field javaField
            && java.lang.reflect.Modifier.isTransient(javaField.getModifiers());
    }

    @Override
    public String toString() {
        return site == null
            ? "InjectionPoint[lookup of " + argument.getType().getName() + "]"
            : "InjectionPoint[" + site.declaringClass().getName() + "#" + site.memberName() + "]";
    }
}
