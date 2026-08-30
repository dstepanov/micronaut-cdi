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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.VoidType;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * The types of the language model, read back off the classes with reflection.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionTypes {

    private ReflectionTypes() {
    }

    /**
     * The type of the language model that the given reflected type is.
     *
     * @param type The reflected type
     * @return The type
     */
    public static Type of(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            List<Type> arguments = new ArrayList<>();
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(of(argument));
            }
            return new Parameterized((ClassType) of(parameterized.getRawType()), List.copyOf(arguments));
        }
        if (type instanceof Class<?> aClass) {
            if (aClass.equals(void.class)) {
                return new OfVoid();
            }
            if (aClass.isArray()) {
                return new ArrayOf(of(aClass.getComponentType()));
            }
            if (aClass.isPrimitive()) {
                return new Primitive(aClass.getName());
            }
            return new OfClass(new ReflectionClassInfo(aClass));
        }
        throw new IllegalArgumentException("The type " + type + " is not one the language model describes here");
    }

    /**
     * The annotations of a type, which are none: an annotation written on a use of a type is not read back off
     * the class here.
     */
    private abstract static class NoAnnotations implements Type {

        @Override
        public final boolean hasAnnotation(Class<? extends Annotation> annotationType) {
            return false;
        }

        @Override
        public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return false;
        }

        @Override
        public final <T extends Annotation> @Nullable AnnotationInfo annotation(Class<T> annotationType) {
            return null;
        }

        @Override
        public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(
            Class<T> annotationType) {
            return List.of();
        }

        @Override
        public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
            return List.of();
        }

        @Override
        public final Collection<AnnotationInfo> annotations() {
            return List.of();
        }
    }

    /**
     * The type {@code void}.
     */
    private static final class OfVoid extends NoAnnotations implements VoidType {

        @Override
        public String name() {
            return "void";
        }
    }

    /**
     * A primitive type.
     */
    private static final class Primitive extends NoAnnotations implements PrimitiveType {

        private final String name;

        private Primitive(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public PrimitiveKind primitiveKind() {
            return switch (name) {
                case "boolean" -> PrimitiveKind.BOOLEAN;
                case "byte" -> PrimitiveKind.BYTE;
                case "short" -> PrimitiveKind.SHORT;
                case "int" -> PrimitiveKind.INT;
                case "long" -> PrimitiveKind.LONG;
                case "float" -> PrimitiveKind.FLOAT;
                case "double" -> PrimitiveKind.DOUBLE;
                case "char" -> PrimitiveKind.CHAR;
                default -> throw new IllegalStateException("Not a primitive type: " + name);
            };
        }
    }

    /**
     * An array type.
     */
    private static final class ArrayOf extends NoAnnotations implements ArrayType {

        private final Type componentType;

        private ArrayOf(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type componentType() {
            return componentType;
        }
    }

    /**
     * A class type.
     */
    private static final class OfClass extends NoAnnotations implements ClassType {

        private final ClassInfo declaration;

        private OfClass(ClassInfo declaration) {
            this.declaration = declaration;
        }

        @Override
        public ClassInfo declaration() {
            return declaration;
        }
    }

    /**
     * A class type named with its type arguments.
     */
    private static final class Parameterized extends NoAnnotations implements ParameterizedType {

        private final ClassType genericClass;
        private final List<Type> typeArguments;

        private Parameterized(ClassType genericClass, List<Type> typeArguments) {
            this.genericClass = genericClass;
            this.typeArguments = typeArguments;
        }

        @Override
        public ClassType genericClass() {
            return genericClass;
        }

        @Override
        public List<Type> typeArguments() {
            return typeArguments;
        }
    }
}
