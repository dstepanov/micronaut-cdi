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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Turns an annotation into the values it was written with, and the values back into an annotation.
 *
 * <p>The specification is written in terms of annotation instances: a bean reports its qualifiers as a set of
 * them, and a program looks a bean up by handing some over. Micronaut records what an annotation was written with
 * as an {@link AnnotationValue} and never materializes the annotation itself. Both directions are needed, and
 * both are here.</p>
 *
 * <p>An annotation materialized here implements {@link Object#equals} and {@link Object#hashCode} exactly as
 * {@code java.lang.annotation.Annotation} specifies them, which is what makes it comparable with the annotation
 * literals the specification's own API is full of: a program that asks whether a bean's qualifiers contain
 * {@code new HairyQualifier(false)} is comparing a literal of its own with one of these.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAnnotations {

    private CdiAnnotations() {
    }

    /**
     * The values an annotation was written with, read off the annotation itself.
     *
     * @param annotation The annotation
     * @param <A>        The annotation type
     * @return The annotation value
     */
    public static <A extends Annotation> AnnotationValue<A> valueOf(A annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Method member : type.getDeclaredMethods()) {
            if (member.getParameterCount() != 0 || member.isSynthetic()) {
                continue;
            }
            if (isNonBinding(member)) {
                // a member excluded from the comparison of qualifiers takes no part in it from either side:
                // whatever it was given here, a bean qualified the same way but for that member still qualifies
                continue;
            }
            try {
                values.put(member.getName(), storedForm(member.invoke(annotation)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("The member " + member.getName() + " of " + type.getName()
                    + " could not be read", e);
            }
        }
        return new AnnotationValue<>(type.getName(), values);
    }

    /**
     * A member value in the form the compiled metadata stores it, so that a value read off a live annotation
     * compares equal to the same value read out of a definition: an enum is stored by its name, and a class by
     * its class value.
     */
    private static @Nullable Object storedForm(@Nullable Object value) {
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Class<?> type) {
            return new io.micronaut.core.annotation.AnnotationClassValue<>(type);
        }
        if (value instanceof Object[] array) {
            Object[] stored = new Object[array.length];
            for (int i = 0; i < array.length; i++) {
                stored[i] = storedForm(array[i]);
            }
            return stored;
        }
        return value;
    }

    /**
     * The annotation the given values describe.
     *
     * @param type  The annotation type
     * @param value The values it was written with, if any were recorded
     * @param <A>   The annotation type
     * @return The annotation
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> A annotationOf(Class<A> type, @Nullable AnnotationValue<?> value) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (Method member : type.getDeclaredMethods()) {
            if (member.getParameterCount() != 0 || member.isSynthetic()) {
                continue;
            }
            Object resolved = value == null ? null : memberValue(value, member.getName());
            if (resolved == null) {
                resolved = member.getDefaultValue();
            } else {
                resolved = ConversionService.SHARED.convertRequired(resolved, member.getReturnType());
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
     * Whether two annotations are the same as far as binding one thing to another goes, which is what the
     * container is asked when it compares two qualifiers or two interceptor bindings.
     *
     * <p>They are the same when they are of the same type and every member that takes part in the comparison is
     * equal. Which members those are was decided by whoever wrote the annotation, with {@code Nonbinding}, and
     * reading the values off the annotation leaves those out already.</p>
     *
     * @param one   The one annotation
     * @param other The other
     * @return Whether they are the same for the purposes of binding
     */
    public static boolean areEquivalent(Annotation one, Annotation other) {
        if (!one.annotationType().equals(other.annotationType())) {
            return false;
        }
        return valueOf(one).equals(valueOf(other));
    }

    /**
     * The hash code of an annotation over the members that take part in the comparison, so that two annotations
     * the container calls the same hash the same.
     *
     * @param annotation The annotation
     * @return The hash code
     */
    public static int bindingHashCode(Annotation annotation) {
        int hash = annotation.annotationType().getName().hashCode();
        for (Map.Entry<CharSequence, Object> member : valueOf(annotation).getValues().entrySet()) {
            Object value = member.getValue();
            hash += (127 * member.getKey().toString().hashCode())
                ^ (value == null ? 0 : Literal.valueHashCode(value));
        }
        return hash;
    }

    /**
     * Whether the member is excluded from the comparison of two annotations, which the specification says with
     * {@code jakarta.enterprise.util.Nonbinding}.
     */
    private static boolean isNonBinding(Method member) {
        if (ExtensionQualifiers.isNonbindingMember(member.getDeclaringClass().getName(), member.getName())) {
            // an extension said so during discovery, into the compiled metadata rather than onto the class
            return true;
        }
        for (Annotation annotation : member.getAnnotations()) {
            if ("jakarta.enterprise.util.Nonbinding".equals(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Object memberValue(AnnotationValue<?> value, String name) {
        for (Map.Entry<CharSequence, Object> member : value.getValues().entrySet()) {
            if (name.contentEquals(member.getKey())) {
                return member.getValue();
            }
        }
        return null;
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

        static int valueHashCode(Object value) {
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
