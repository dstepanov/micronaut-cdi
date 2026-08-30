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
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * One injection point of a bean, described from what was compiled.
 *
 * <p>The type and the qualifiers come from the argument Micronaut resolved for the point, generics included. The
 * member is the one part the specification asks for that compiled metadata does not carry as an object, so it is
 * looked up reflectively — here, where a program asked to be told about the bean, not anywhere a bean is
 * resolved or injected.</p>
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

    CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, @Nullable Class<?> declaringClass,
                      @Nullable String memberName, boolean field) {
        this(bean, argument, argument, declaringClass, memberName, field);
    }

    private CdiInjectionPoint(@Nullable Bean<?> bean, Argument<?> argument, Argument<?> memberArgument,
                              @Nullable Class<?> declaringClass, @Nullable String memberName, boolean field) {
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
        if (memberName == null) {
            // an injection point that stands for a programmatic lookup has no member
            return null;
        }
        for (Class<?> type = declaringClass; type != null && type != Object.class; type = type.getSuperclass()) {
            if (field) {
                try {
                    return type.getDeclaredField(memberName);
                } catch (NoSuchFieldException e) {
                    // declared further up
                }
            } else if ("<init>".equals(memberName)) {
                java.lang.reflect.Constructor<?> fallback = null;
                for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (constructor.isSynthetic()) {
                        continue;
                    }
                    // the bean constructor is the injected one where one is marked; else the one that takes
                    // what this point injects
                    if (constructor.isAnnotationPresent(jakarta.inject.Inject.class)
                        || takesTheArgument(constructor.getParameterTypes())) {
                        return constructor;
                    }
                    if (fallback == null) {
                        fallback = constructor;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            } else {
                Method fallback = null;
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(memberName)) {
                        continue;
                    }
                    // of same-named overloads, the injected member is the one that takes what this point
                    // injects
                    if (takesTheArgument(method.getParameterTypes())) {
                        return method;
                    }
                    if (fallback == null) {
                        fallback = method;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
        }
        return null;
    }

    private boolean takesTheArgument(Class<?>[] parameterTypes) {
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.equals(memberArgument.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * This injection point seen with the type a lookup selected: a bean obtained through {@code Instance} has
     * the lookup's injection point as its metadata, with the type it was selected as.
     *
     * @param selected The selected type
     * @return The injection point, typed as selected
     */
    CdiInjectionPoint viewedAs(Argument<?> selected) {
        Argument<?> viewed = Argument.of(selected.getType(), argument.getAnnotationMetadata(),
            selected.getTypeParameters());
        // the member is still the one that was written, whatever the lookup was selected as
        return new CdiInjectionPoint(bean, viewed, memberArgument, declaringClass, memberName, field);
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
        boolean isField = segment
            instanceof io.micronaut.context.AbstractBeanResolutionContext.FieldSegment<?, ?>;
        String member = segment
            instanceof io.micronaut.context.AbstractBeanResolutionContext.ConstructorSegment
            ? "<init>" : segment.getName();
        return new CdiInjectionPoint(bean, segment.getArgument(), declaring, member, isField);
    }

    @Override
    public Annotated getAnnotated() {
        Member member = getMember();
        if (member instanceof java.lang.reflect.Field javaField) {
            return new ReflectiveAnnotatedField(javaField);
        }
        if (member instanceof java.lang.reflect.Executable executable) {
            int position = positionOf(executable);
            if (position >= 0) {
                return new ReflectiveAnnotatedParameter(executable, position);
            }
        }
        throw new UnsupportedOperationException("The annotated model of this injection point cannot be read "
            + "back from the compiled class");
    }

    private int positionOf(java.lang.reflect.Executable executable) {
        Class<?> raw = memberArgument.getType();
        java.lang.reflect.Parameter[] parameters = executable.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(raw) && parameters[i].getName().equals(memberArgument.getName())) {
                return i;
            }
        }
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(raw)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isDelegate() {
        return false;
    }

    @Override
    public boolean isTransient() {
        return getMember() instanceof java.lang.reflect.Field javaField
            && java.lang.reflect.Modifier.isTransient(javaField.getModifiers());
    }

    @Override
    public String toString() {
        return declaringClass == null
            ? "InjectionPoint[lookup of " + argument.getType().getName() + "]"
            : "InjectionPoint[" + declaringClass.getName() + "#" + memberName + "]";
    }
}
