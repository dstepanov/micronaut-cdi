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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * One injection point of a bean, described from what was compiled.
 *
 * <p>The type and the qualifiers come from the argument Micronaut resolved for the point, generics included. The
 * member and its annotated model are the parts the specification asks for that compiled metadata does not carry
 * as objects: they are reflection objects, handed out through {@link CdiReflection} when a program asks for
 * them, and not read anywhere a bean is resolved or injected.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInjectionPoint implements InjectionPoint {

    private final @Nullable Bean<?> bean;
    private final Argument<?> argument;
    private final Argument<?> memberArgument;
    private final @Nullable Class<?> declaringClass;
    private final @Nullable String memberName;
    private final boolean field;
    private final java.util.List<CdiQualifier> selectedQualifiers;

    CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, @Nullable Class<?> declaringClass,
                      @Nullable String memberName, boolean field) {
        this(bean, argument, argument, declaringClass, memberName, field, java.util.List.of());
    }

    private CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, Argument<?> memberArgument,
                              @Nullable Class<?> declaringClass, @Nullable String memberName, boolean field,
                              java.util.List<CdiQualifier> selectedQualifiers) {
        this.selectedQualifiers = selectedQualifiers;
        this.bean = bean;
        this.argument = argument;
        this.memberArgument = memberArgument;
        this.declaringClass = declaringClass;
        this.memberName = memberName;
        this.field = field;
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
    public @Nullable Bean<?> getBean() {
        return bean;
    }

    @Override
    public @Nullable Member getMember() {
        String memberName = this.memberName;
        Class<?> declaringClass = this.declaringClass;
        if (memberName == null || declaringClass == null) {
            // an injection point that stands for a programmatic lookup has no member
            return null;
        }
        return CdiReflection.current("InjectionPoint.getMember()")
            .member(declaringClass, memberName, field, memberArgument.getType());
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
        return new CdiInjectionPoint(bean, viewed, memberArgument, declaringClass, memberName, field, qualifiers);
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
        boolean isField = segment instanceof io.micronaut.inject.FieldInjectionPoint<?, ?>;
        String member = isConstructorArgument(segment) ? "<init>" : segment.getName();
        return new CdiInjectionPoint(bean, segment.getArgument(), declaring, member, isField);
    }

    /**
     * Whether the segment is an argument of the constructor of the bean. The factory method of a produced bean is
     * the constructor of that bean to Micronaut, and a method all the same, whose name is the member's.
     */
    private static boolean isConstructorArgument(io.micronaut.context.BeanResolutionContext.Segment<?, ?> segment) {
        return segment instanceof io.micronaut.inject.ArgumentInjectionPoint<?, ?> argument
            && argument.getOuterInjectionPoint() instanceof io.micronaut.inject.ConstructorInjectionPoint<?> outer
            && !(outer instanceof io.micronaut.inject.MethodInjectionPoint<?, ?>);
    }

    @Override
    public Annotated getAnnotated() {
        String memberName = this.memberName;
        Class<?> declaringClass = this.declaringClass;
        if (memberName == null || declaringClass == null) {
            throw new UnsupportedOperationException("An injection point that stands for a programmatic lookup "
                + "has no annotated model");
        }
        CdiReflection reflection = CdiReflection.current("InjectionPoint.getAnnotated()");
        Member member = reflection.member(declaringClass, memberName, field, memberArgument.getType());
        if (member == null) {
            throw new UnsupportedOperationException("The annotated model of this injection point cannot be read "
                + "back from the compiled class");
        }
        return reflection.annotated(member, memberArgument.getType(), memberArgument.getName());
    }

    @Override
    public boolean isDelegate() {
        return false;
    }

    @Override
    public boolean isTransient() {
        // only a field can be transient, and whether one is is read off the field itself
        return field && getMember() instanceof java.lang.reflect.Field javaField
            && java.lang.reflect.Modifier.isTransient(javaField.getModifiers());
    }

    @Override
    public String toString() {
        return declaringClass == null
            ? "InjectionPoint[lookup of " + argument.getType().getName() + "]"
            : "InjectionPoint[" + declaringClass.getName() + "#" + memberName + "]";
    }
}
