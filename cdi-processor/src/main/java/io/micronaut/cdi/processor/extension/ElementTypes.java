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
package io.micronaut.cdi.processor.extension;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
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
import java.util.Map;
import java.util.function.Predicate;

/**
 * Reads a Micronaut type as the type of the language model a build compatible extension is written against.
 *
 * <p>The two describe the same thing and mostly agree on how: a class, an array of something, a parameterized
 * type, a primitive, void. What the language model asks for that Micronaut does not carry is the annotations
 * written on a use of a type rather than on its declaration; a type here reports none of those.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementTypes {

    private ElementTypes() {
    }

    /**
     * The type of the language model that the given Micronaut type is.
     *
     * @param element The Micronaut type
     * @return The type
     */
    public static Type of(ClassElement element) {
        if (element.isVoid()) {
            return new Void();
        }
        if (element.isArray()) {
            return new Array(of(element.fromArray()));
        }
        if (element.isPrimitive()) {
            return new Primitive(element.getName());
        }
        ClassType raw = new Class(new ElementClassInfo(element));
        if (element.getTypeArguments().isEmpty()) {
            return raw;
        }
        List<Type> arguments = new ArrayList<>();
        for (Map.Entry<String, ClassElement> argument : element.getTypeArguments().entrySet()) {
            arguments.add(of(argument.getValue()));
        }
        return new Parameterized(raw, List.copyOf(arguments));
    }

    /**
     * The Micronaut element a type of this model stands for: what {@link #of(ClassElement)} was given, so that
     * a type handed back by a builder can be composed with rather than only read.
     *
     * @param type The type
     * @return The element
     */
    public static ClassElement elementOf(Type type) {
        if (type instanceof Void) {
            return io.micronaut.inject.ast.PrimitiveElement.VOID;
        }
        if (type instanceof Primitive primitive) {
            return io.micronaut.inject.ast.PrimitiveElement.valueOf(primitive.name());
        }
        if (type instanceof Array array) {
            return elementOf(array.componentType()).toArray();
        }
        if (type instanceof Class classType && classType.declaration() instanceof ElementClassInfo info) {
            return info.classElement();
        }
        if (type instanceof Parameterized parameterized
            && parameterized.genericClass().declaration() instanceof ElementClassInfo info) {
            return info.classElement();
        }
        throw new IllegalArgumentException("The type " + type + " was not composed by this model");
    }

    /**
     * The annotations of a type, which are none: Micronaut records the annotations of a declaration rather than
     * the ones written on a use of a type.
     */
    private abstract static class NoAnnotations implements Type {

        @Override
        public final boolean hasAnnotation(java.lang.Class<? extends Annotation> annotationType) {
            return false;
        }

        @Override
        public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return false;
        }

        @Override
        public final <T extends Annotation> @Nullable AnnotationInfo annotation(
            java.lang.Class<T> annotationType) {
            return null;
        }

        @Override
        public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(
            java.lang.Class<T> annotationType) {
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
    private static final class Void extends NoAnnotations implements VoidType {

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
    private static final class Array extends NoAnnotations implements ArrayType {

        private final Type componentType;

        private Array(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type componentType() {
            return componentType;
        }
    }

    /**
     * A class type, which is a class named without its type arguments.
     */
    @SuppressWarnings("AvoidCommonTypeNames")
    private static final class Class extends NoAnnotations implements ClassType {

        private final ClassInfo declaration;

        private Class(ClassInfo declaration) {
            this.declaration = declaration;
        }

        @Override
        public ClassInfo declaration() {
            return declaration;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ClassType other && declaration.name().equals(other.declaration().name());
        }

        @Override
        public int hashCode() {
            return declaration.name().hashCode();
        }

        @Override
        public String toString() {
            return declaration.name();
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

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other && genericClass.equals(other.genericClass())
                && typeArguments.equals(other.typeArguments());
        }

        @Override
        public int hashCode() {
            return genericClass.hashCode() * 31 + typeArguments.hashCode();
        }

        @Override
        public String toString() {
            return genericClass + "<" + typeArguments + ">";
        }
    }
}
