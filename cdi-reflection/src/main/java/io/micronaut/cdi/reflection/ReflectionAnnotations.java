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

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.AnnotationTarget;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The annotations of the language model, read back off the classes with reflection.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionAnnotations {

    private ReflectionAnnotations() {
    }

    /**
     * The annotations declared on the given element, as the language model reports them.
     *
     * @param element The element
     * @return The annotations
     */
    public static Collection<AnnotationInfo> of(AnnotatedElement element) {
        List<AnnotationInfo> annotations = new ArrayList<>();
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            annotations.add(new Info(annotation));
        }
        return annotations;
    }

    /**
     * The annotation of the given type declared on the element, if it declares one.
     *
     * @param element The element
     * @param type    The annotation type
     * @return The annotation, or {@code null}
     */
    public static @Nullable AnnotationInfo of(AnnotatedElement element, Class<? extends Annotation> type) {
        Annotation annotation = element.getDeclaredAnnotation(type);
        return annotation == null ? null : new Info(annotation);
    }

    /**
     * The annotation instance an annotation of the language model was read from.
     *
     * @param annotation The annotation of the language model
     * @return The annotation instance
     */
    /**
     * The annotation as the language model describes it.
     *
     * @param annotation The annotation
     * @return Its description
     */
    public static AnnotationInfo infoOf(Annotation annotation) {
        return new Info(annotation);
    }

    public static Annotation annotationOf(AnnotationInfo annotation) {
        if (annotation instanceof Info info) {
            return info.annotation();
        }
        throw new IllegalArgumentException("The annotation " + annotation.name() + " was not read from a class");
    }

    /**
     * One annotation, read off the element that carries it.
     *
     * @param annotation The annotation
     */
    private record Info(Annotation annotation) implements AnnotationInfo {

        @Override
        public ClassInfo declaration() {
            return new ReflectionClassInfo(annotation.annotationType());
        }

        @Override
        public String name() {
            return annotation.annotationType().getName();
        }

        @Override
        public boolean hasMember(String name) {
            return member(name) != null;
        }

        @Override
        public @Nullable AnnotationMember member(String name) {
            for (Method member : annotation.annotationType().getDeclaredMethods()) {
                if (member.getName().equals(name) && member.getParameterCount() == 0) {
                    return new Member(read(member));
                }
            }
            return null;
        }

        @Override
        public Map<String, AnnotationMember> members() {
            Map<String, AnnotationMember> members = new LinkedHashMap<>();
            for (Method member : annotation.annotationType().getDeclaredMethods()) {
                if (member.getParameterCount() == 0 && !member.isSynthetic()) {
                    members.put(member.getName(), new Member(read(member)));
                }
            }
            return members;
        }

        private Object read(Method member) {
            try {
                return member.invoke(annotation);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The member " + member.getName() + " of " + name()
                    + " could not be read", e);
            }
        }

        @Override
        public String toString() {
            return "@" + name();
        }
    }

    /**
     * One member of an annotation, read off the annotation.
     *
     * @param value The value of the member
     */
    private record Member(Object value) implements AnnotationMember {

        @Override
        public Kind kind() {
            if (value instanceof Boolean) {
                return Kind.BOOLEAN;
            }
            if (value instanceof Byte) {
                return Kind.BYTE;
            }
            if (value instanceof Short) {
                return Kind.SHORT;
            }
            if (value instanceof Integer) {
                return Kind.INT;
            }
            if (value instanceof Long) {
                return Kind.LONG;
            }
            if (value instanceof Float) {
                return Kind.FLOAT;
            }
            if (value instanceof Double) {
                return Kind.DOUBLE;
            }
            if (value instanceof Character) {
                return Kind.CHAR;
            }
            if (value instanceof Enum<?>) {
                return Kind.ENUM;
            }
            if (value instanceof Class<?>) {
                return Kind.CLASS;
            }
            if (value instanceof Annotation) {
                return Kind.NESTED_ANNOTATION;
            }
            if (value.getClass().isArray()) {
                return Kind.ARRAY;
            }
            return Kind.STRING;
        }

        @Override
        public boolean asBoolean() {
            return (Boolean) value;
        }

        @Override
        public byte asByte() {
            return ((Number) value).byteValue();
        }

        @Override
        public short asShort() {
            return ((Number) value).shortValue();
        }

        @Override
        public int asInt() {
            return ((Number) value).intValue();
        }

        @Override
        public long asLong() {
            return ((Number) value).longValue();
        }

        @Override
        public float asFloat() {
            return ((Number) value).floatValue();
        }

        @Override
        public double asDouble() {
            return ((Number) value).doubleValue();
        }

        @Override
        public char asChar() {
            return (Character) value;
        }

        @Override
        public String asString() {
            return value.toString();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <E extends Enum<E>> E asEnum(Class<E> enumType) {
            return (E) value;
        }

        @Override
        public ClassInfo asEnumClass() {
            return new ReflectionClassInfo(((Enum<?>) value).getDeclaringClass());
        }

        @Override
        public String asEnumConstant() {
            return ((Enum<?>) value).name();
        }

        @Override
        public Type asType() {
            return ReflectionTypes.of((Class<?>) value);
        }

        @Override
        public AnnotationInfo asNestedAnnotation() {
            return new Info((Annotation) value);
        }

        @Override
        public List<AnnotationMember> asArray() {
            List<AnnotationMember> members = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                members.add(new Member(Array.get(value, i)));
            }
            return members;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /**
     * What every target of the language model read off a class has in common.
     */
    abstract static class Target implements AnnotationTarget {

        /**
         * The element the annotations are read off.
         *
         * @return The element
         */
        abstract AnnotatedElement annotated();

        @Override
        public final boolean hasAnnotation(Class<? extends Annotation> annotationType) {
            return annotated().isAnnotationPresent(annotationType);
        }

        @Override
        public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return annotations().stream().anyMatch(predicate);
        }

        @Override
        public final <T extends Annotation> @Nullable AnnotationInfo annotation(Class<T> annotationType) {
            return ReflectionAnnotations.of(annotated(), annotationType);
        }

        @Override
        public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(
            Class<T> annotationType) {
            List<AnnotationInfo> found = new ArrayList<>();
            for (Annotation annotation : annotated().getDeclaredAnnotationsByType(annotationType)) {
                found.add(new Info(annotation));
            }
            return found;
        }

        @Override
        public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
            return annotations().stream().filter(predicate).toList();
        }

        @Override
        public final Collection<AnnotationInfo> annotations() {
            return ReflectionAnnotations.of(annotated());
        }
    }
}
