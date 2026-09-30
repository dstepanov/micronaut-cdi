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
package io.micronaut.cdi.reflection;

import io.micronaut.cdi.runtime.CdiReflection;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Answers the parts of the specification's API that hand out reflection objects, by reflection.
 *
 * <p>An annotation materialized here implements {@link Object#equals} and {@link Object#hashCode} exactly as
 * {@code java.lang.annotation.Annotation} specifies them, which is what makes it comparable with the annotation
 * literals the specification's own API is full of: a program that asks whether a bean's qualifiers contain
 * {@code new HairyQualifier(false)} is comparing a literal of its own with one of these.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ReflectiveCdi implements CdiReflection {

    @SuppressWarnings("unchecked")
    @Override
    public <A extends Annotation> A annotation(Class<A> type, @Nullable AnnotationValue<?> value) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (Method member : type.getDeclaredMethods()) {
            if (member.getParameterCount() != 0 || member.isSynthetic()) {
                continue;
            }
            Object resolved = value == null ? null : memberValue(value, member.getName());
            if (resolved == null) {
                resolved = member.getDefaultValue();
            } else {
                resolved = resolve(resolved, member.getReturnType());
            }
            if (resolved == null) {
                throw new IllegalArgumentException("The member " + member.getName() + " of " + type.getName()
                    + " has neither a value nor a default");
            }
            members.put(member.getName(), resolved);
        }
        return (A) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            new Literal(type, members)
        );
    }

    /**
     * A member as the type its annotation interface declares: a nested annotation, recorded as its values, is
     * an instance of its own annotation type, and everything else is converted the way the values of compiled
     * metadata are.
     */
    @SuppressWarnings("unchecked")
    private Object resolve(Object recorded, Class<?> memberType) {
        if (recorded instanceof AnnotationValue<?> nested && memberType.isAnnotation()) {
            return annotation((Class<? extends Annotation>) memberType, nested);
        }
        if (memberType.isArray() && memberType.getComponentType().isAnnotation()
            && recorded instanceof Object[] nested) {
            Class<? extends Annotation> componentType = (Class<? extends Annotation>) memberType.getComponentType();
            Object annotations = Array.newInstance(componentType, nested.length);
            for (int i = 0; i < nested.length; i++) {
                Array.set(annotations, i, nested[i] instanceof AnnotationValue<?> value
                    ? annotation(componentType, value) : nested[i]);
            }
            return annotations;
        }
        return ConversionService.SHARED.convertRequired(recorded, memberType);
    }

    private static @Nullable Object memberValue(AnnotationValue<?> value, String name) {
        for (Map.Entry<CharSequence, Object> member : value.getValues().entrySet()) {
            if (name.contentEquals(member.getKey())) {
                return member.getValue();
            }
        }
        return null;
    }

    @Override
    public @Nullable Member member(Class<?> declaringClass, String memberName, boolean field, Class<?> injectedType) {
        for (Class<?> type = declaringClass; type != null && type != Object.class; type = type.getSuperclass()) {
            if (field) {
                try {
                    return type.getDeclaredField(memberName);
                } catch (NoSuchFieldException e) {
                    // declared further up
                }
            } else if ("<init>".equals(memberName)) {
                Constructor<?> fallback = null;
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (constructor.isSynthetic()) {
                        continue;
                    }
                    // the bean constructor is the injected one where one is marked; else the one that takes
                    // what the point injects
                    if (constructor.isAnnotationPresent(jakarta.inject.Inject.class)
                        || takes(constructor.getParameterTypes(), injectedType)) {
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
                    // of same-named overloads, the injected member is the one that takes what the point
                    // injects
                    if (takes(method.getParameterTypes(), injectedType)) {
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

    private static boolean takes(Class<?>[] parameterTypes, Class<?> injectedType) {
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.equals(injectedType)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Annotated annotated(Member member, Class<?> injectedType, String injectedName) {
        if (member instanceof Field field) {
            return new ReflectiveAnnotatedField(field);
        }
        if (member instanceof Executable executable) {
            int position = positionOf(executable, injectedType, injectedName);
            if (position >= 0) {
                return new ReflectiveAnnotatedParameter(executable, position);
            }
        }
        throw new UnsupportedOperationException("The annotated model of this injection point cannot be read "
            + "back from the compiled class");
    }

    private static int positionOf(Executable executable, Class<?> injectedType, String injectedName) {
        Parameter[] parameters = executable.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(injectedType) && parameters[i].getName().equals(injectedName)) {
                return i;
            }
        }
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(injectedType)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isAnnotated(Class<? extends Annotation> annotationType,
                               Class<? extends Annotation> metaAnnotation) {
        return annotationType.isAnnotationPresent(metaAnnotation);
    }

    @Override
    public Set<Annotation> metaAnnotationsOf(Class<? extends Annotation> annotationType) {
        Set<Annotation> annotations = new LinkedHashSet<>();
        for (Annotation annotation : annotationType.getAnnotations()) {
            if (!annotation.annotationType().getName().startsWith("java.lang.annotation.")) {
                annotations.add(annotation);
            }
        }
        return annotations;
    }

    /**
     * An annotation that behaves as the language specification says an annotation instance does.
     *
     * @param type    The annotation type
     * @param members The members it was written with, every one of them resolved to a value
     */
    private record Literal(Class<? extends Annotation> type, Map<String, Object> members)
        implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) {
            String name = method.getName();
            if (members.containsKey(name) && method.getParameterCount() == 0) {
                return members.get(name);
            }
            return switch (name) {
                case "annotationType" -> type;
                case "hashCode" -> annotationHashCode();
                case "toString" -> annotationToString();
                case "equals" -> args != null && args.length == 1 && isEqualTo(args[0]);
                default -> throw new UnsupportedOperationException(name);
            };
        }

        /**
         * The hash code of an annotation is the sum, over its members, of the member's name hashed and the
         * member's value hashed, which is what {@code java.lang.annotation.Annotation} specifies.
         */
        private int annotationHashCode() {
            int hash = 0;
            for (Map.Entry<String, Object> member : members.entrySet()) {
                hash += (127 * member.getKey().hashCode()) ^ valueHashCode(member.getValue());
            }
            return hash;
        }

        /**
         * Two annotations are equal when they are of the same type and every member is equal, comparing the
         * members of an array member one by one.
         */
        private boolean isEqualTo(@Nullable Object other) {
            if (!(other instanceof Annotation annotation) || !type.equals(annotation.annotationType())) {
                return false;
            }
            for (Map.Entry<String, Object> member : members.entrySet()) {
                Object otherValue;
                try {
                    otherValue = annotation.annotationType()
                        .getDeclaredMethod(member.getKey())
                        .invoke(annotation);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
                if (!valueEquals(member.getValue(), otherValue)) {
                    return false;
                }
            }
            return true;
        }

        private String annotationToString() {
            StringJoiner joiner = new StringJoiner(", ", "@" + type.getName() + "(", ")");
            members.forEach((name, value) -> joiner.add(name + "=" + value));
            return members.isEmpty() ? "@" + type.getName() : joiner.toString();
        }

        private static int valueHashCode(Object value) {
            if (value.getClass().isArray()) {
                int hash = 1;
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    hash = 31 * hash + valueHashCode(Objects.requireNonNull(Array.get(value, i)));
                }
                return hash;
            }
            return value.hashCode();
        }

        private static boolean valueEquals(Object one, @Nullable Object other) {
            if (other == null) {
                return false;
            }
            if (one.getClass().isArray() && other.getClass().isArray()) {
                int length = Array.getLength(one);
                if (length != Array.getLength(other)) {
                    return false;
                }
                for (int i = 0; i < length; i++) {
                    if (!valueEquals(Objects.requireNonNull(Array.get(one, i)), Array.get(other, i))) {
                        return false;
                    }
                }
                return true;
            }
            return one.equals(other);
        }
    }
}
