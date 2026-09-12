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
import jakarta.enterprise.lang.model.types.TypeVariable;
import jakarta.enterprise.lang.model.types.VoidType;
import jakarta.enterprise.lang.model.types.WildcardType;
import org.jspecify.annotations.Nullable;

import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reads one of the compiler's types as the type of the language model a build compatible extension is written
 * against.
 *
 * <p>A type of the language model carries the annotations written on the use of it, at every depth: on a type
 * argument, on one dimension of an array, on a bound of a type variable, on a wildcard. Micronaut's model of a
 * type is built to answer what a type is, not where each annotation of a use of it was written, so these types
 * are read from the compiler's own, where a type annotation is part of the type.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class MirrorTypes {

    private MirrorTypes() {
    }

    /**
     * The type of the language model that one of the compiler's types is.
     *
     * @param mirror The compiler's type
     * @return The type
     */
    static Type of(TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case VOID, NONE -> new Void(mirror);
            case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR -> new Primitive(mirror);
            case ARRAY -> new Array(mirror, of(((javax.lang.model.type.ArrayType) mirror).getComponentType()));
            case TYPEVAR -> new Variable((javax.lang.model.type.TypeVariable) mirror, mirror);
            case WILDCARD -> new Wildcard((javax.lang.model.type.WildcardType) mirror);
            case DECLARED, ERROR -> declared((DeclaredType) mirror);
            default -> throw new IllegalStateException("The type " + mirror + " is not one the language model has");
        };
    }

    /**
     * The type a declaration declares: the type of a field or a parameter, or what a method returns.
     *
     * <p>The compiler keeps the annotations written on a use of a type with the type, except where the type is a
     * type variable: there it hands back the variable the declaration of it introduced, carrying nothing of the
     * use. What was written on that use is still on the declaration that wrote it, among the annotations of the
     * declaration that could have been written on a type.</p>
     *
     * @param mirror      The compiler's type
     * @param declaration The declaration the type belongs to
     * @return The type
     */
    static Type ofDeclared(TypeMirror mirror, AnnotatedConstruct declaration) {
        if (mirror.getKind() == TypeKind.TYPEVAR && mirror.getAnnotationMirrors().isEmpty()) {
            return new Variable((javax.lang.model.type.TypeVariable) mirror, declaration, null, true);
        }
        return of(mirror);
    }

    /**
     * The type of the language model that one of the compiler's types is, or {@code null} when the compiler
     * reports that there is no type there — which is how it answers for the receiver of a static method.
     *
     * @param mirror The compiler's type
     * @return The type, or {@code null}
     */
    static @Nullable Type ofPresent(TypeMirror mirror) {
        return mirror.getKind() == TypeKind.NONE ? null : of(mirror);
    }

    /**
     * The type variable one of the compiler's type parameters declares. The annotations are the ones written on
     * the parameter and the bounds the ones it declares, which is what the declaration of a variable carries and
     * a use of it does not.
     *
     * @param parameter The compiler's type parameter
     * @return The type variable
     */
    static TypeVariable ofParameter(TypeParameterElement parameter) {
        return new Variable((javax.lang.model.type.TypeVariable) parameter.asType(), parameter,
            boundsOf(parameter.getBounds()));
    }

    /**
     * A class type naming the given class, with no annotations: the implicit upper bound of an unbounded
     * wildcard, which the source wrote nothing of.
     *
     * @param name The binary name
     * @return The type
     */
    static ClassType classOf(String name) {
        return new Class(name, null, false);
    }

    /**
     * The class type a constructor returns, carrying the annotations written before the constructor's name that
     * could have been written on a type.
     *
     * <p>The compiler reports the type a constructor returns as {@code void}, the way a class file declares it,
     * and keeps the annotations written on the use of the class it constructs with the constructor itself. Which
     * of those were written on the type rather than on the constructor is what their own targets say.</p>
     *
     * @param name        The binary name of the class the constructor constructs
     * @param constructor The compiler's constructor
     * @return The type
     */
    static ClassType ofConstructorReturn(String name, AnnotatedConstruct constructor) {
        return new Class(name, constructor, true);
    }

    private static Type declared(DeclaredType mirror) {
        String name = ExtensionSourceModel.nameOf((TypeElement) mirror.asElement());
        Class raw = new Class(name, mirror, false);
        if (mirror.getTypeArguments().isEmpty()) {
            return raw;
        }
        return new Parameterized(mirror, raw, boundsOf(mirror.getTypeArguments()));
    }

    private static List<Type> boundsOf(List<? extends TypeMirror> mirrors) {
        List<Type> types = new ArrayList<>(mirrors.size());
        for (TypeMirror mirror : mirrors) {
            // a variable bounded by an intersection declares each part of it as a bound of its own
            if (mirror instanceof IntersectionType intersection) {
                types.addAll(boundsOf(intersection.getBounds()));
            } else {
                types.add(of(mirror));
            }
        }
        return List.copyOf(types);
    }

    /**
     * The annotations written on the use of a type, which the compiler keeps on the type itself.
     */
    private abstract static class Annotated implements Type {

        private final @Nullable AnnotatedConstruct annotated;
        private final boolean typeUseOnly;

        Annotated(@Nullable AnnotatedConstruct annotated) {
            this(annotated, false);
        }

        Annotated(@Nullable AnnotatedConstruct annotated, boolean typeUseOnly) {
            this.annotated = annotated;
            this.typeUseOnly = typeUseOnly;
        }

        @Override
        public final boolean hasAnnotation(java.lang.Class<? extends Annotation> annotationType) {
            return annotation(annotationType) != null;
        }

        @Override
        public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return annotations().stream().anyMatch(predicate);
        }

        @Override
        public final <T extends Annotation> @Nullable AnnotationInfo annotation(
            java.lang.Class<T> annotationType) {
            for (AnnotationInfo annotation : annotations()) {
                if (annotation.name().equals(annotationType.getName())) {
                    return annotation;
                }
            }
            return null;
        }

        @Override
        public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(
            java.lang.Class<T> annotationType) {
            return ExtensionAnnotations.repeatableIn(annotations(), annotationType.getName());
        }

        @Override
        public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
            return annotations().stream().filter(predicate).toList();
        }

        @Override
        public final List<AnnotationInfo> annotations() {
            if (annotated == null) {
                return List.of();
            }
            Elements utilities = ExtensionSourceModel.elementUtils();
            List<AnnotationInfo> found = new ArrayList<>();
            for (AnnotationMirror mirror : annotated.getAnnotationMirrors()) {
                String name = ExtensionSourceModel.nameOf(mirror);
                if (!ExtensionAnnotationTypes.isRuntimeRetained(name)) {
                    continue;
                }
                if (typeUseOnly && !ExtensionAnnotations.isTypeUse(name)) {
                    continue;
                }
                found.add(new MirrorAnnotationInfo(mirror, utilities));
            }
            return found;
        }
    }

    /**
     * The type {@code void}, and the absence of a type, which carries no annotation of its own: an annotation
     * written on a method returning {@code void} is written on the method.
     */
    private static final class Void extends Annotated implements VoidType {

        private Void(TypeMirror mirror) {
            super(null);
        }

        @Override
        public String name() {
            return "void";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VoidType;
        }

        @Override
        public int hashCode() {
            return VoidType.class.hashCode();
        }

        @Override
        public String toString() {
            return "void";
        }
    }

    /**
     * A primitive type.
     */
    private static final class Primitive extends Annotated implements PrimitiveType {

        private final TypeKind kind;

        private Primitive(TypeMirror mirror) {
            super(mirror);
            this.kind = mirror.getKind();
        }

        @Override
        public String name() {
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public PrimitiveKind primitiveKind() {
            return switch (kind) {
                case BOOLEAN -> PrimitiveKind.BOOLEAN;
                case BYTE -> PrimitiveKind.BYTE;
                case SHORT -> PrimitiveKind.SHORT;
                case INT -> PrimitiveKind.INT;
                case LONG -> PrimitiveKind.LONG;
                case FLOAT -> PrimitiveKind.FLOAT;
                case DOUBLE -> PrimitiveKind.DOUBLE;
                case CHAR -> PrimitiveKind.CHAR;
                default -> throw new IllegalStateException("Not a primitive type: " + kind);
            };
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PrimitiveType other && primitiveKind() == other.primitiveKind();
        }

        @Override
        public int hashCode() {
            return primitiveKind().hashCode();
        }

        @Override
        public String toString() {
            return name();
        }
    }

    /**
     * An array type. Each dimension is a type of its own, since each may carry its own annotation.
     */
    private static final class Array extends Annotated implements ArrayType {

        private final Type componentType;

        private Array(TypeMirror mirror, Type componentType) {
            super(mirror);
            this.componentType = componentType;
        }

        @Override
        public Type componentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ArrayType other && componentType.equals(other.componentType());
        }

        @Override
        public int hashCode() {
            return componentType.hashCode() * 31;
        }

        @Override
        public String toString() {
            return componentType + "[]";
        }
    }

    /**
     * A class type, which is a class named without its type arguments.
     */
    @SuppressWarnings("AvoidCommonTypeNames")
    private static final class Class extends Annotated implements ClassType {

        private final String name;

        private Class(String name, @Nullable AnnotatedConstruct annotated, boolean typeUseOnly) {
            super(annotated, typeUseOnly);
            this.name = name;
        }

        @Override
        public ClassInfo declaration() {
            ClassElement declaration = ExtensionAnnotationTypes.declarationOf(name);
            if (declaration == null) {
                throw new IllegalStateException("The type " + name + " is not on the compilation's classpath");
            }
            return new ElementClassInfo(declaration);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ClassType other && name.equals(other.declaration().name());
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A class type named with its type arguments.
     */
    private static final class Parameterized extends Annotated implements ParameterizedType {

        private final ClassType genericClass;
        private final List<Type> typeArguments;

        private Parameterized(TypeMirror mirror, ClassType genericClass, List<Type> typeArguments) {
            super(mirror);
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

    /**
     * A type variable, either where its declaration introduces it or where a type names it.
     */
    private static final class Variable extends Annotated implements TypeVariable {

        private final javax.lang.model.type.TypeVariable mirror;
        private final @Nullable List<Type> declaredBounds;

        private Variable(javax.lang.model.type.TypeVariable mirror, AnnotatedConstruct annotated) {
            this(mirror, annotated, null);
        }

        private Variable(javax.lang.model.type.TypeVariable mirror, AnnotatedConstruct annotated,
                         @Nullable List<Type> declaredBounds) {
            this(mirror, annotated, declaredBounds, false);
        }

        private Variable(javax.lang.model.type.TypeVariable mirror, AnnotatedConstruct annotated,
                         @Nullable List<Type> declaredBounds, boolean typeUseOnly) {
            super(annotated, typeUseOnly);
            this.mirror = mirror;
            this.declaredBounds = declaredBounds;
        }

        @Override
        public String name() {
            return mirror.asElement().getSimpleName().toString();
        }

        @Override
        public List<Type> bounds() {
            // where the variable is named rather than declared, the compiler reports its bound as one type,
            // which is an intersection when the declaration wrote more than one
            return declaredBounds != null ? declaredBounds : boundsOf(List.of(mirror.getUpperBound()));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TypeVariable other && name().equals(other.name());
        }

        @Override
        public int hashCode() {
            return name().hashCode();
        }

        @Override
        public String toString() {
            return name();
        }
    }

    /**
     * A wildcard type. The unbounded wildcard is the one bounded above by {@code java.lang.Object}, which is
     * what it means and how the language model reports it.
     */
    private static final class Wildcard extends Annotated implements WildcardType {

        private final javax.lang.model.type.WildcardType mirror;

        private Wildcard(javax.lang.model.type.WildcardType mirror) {
            super(mirror);
            this.mirror = mirror;
        }

        @Override
        public @Nullable Type upperBound() {
            TypeMirror bound = mirror.getExtendsBound();
            if (bound != null) {
                return of(bound);
            }
            return mirror.getSuperBound() == null ? classOf("java.lang.Object") : null;
        }

        @Override
        public @Nullable Type lowerBound() {
            TypeMirror bound = mirror.getSuperBound();
            return bound == null ? null : of(bound);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof WildcardType other)) {
                return false;
            }
            return java.util.Objects.equals(upperBound(), other.upperBound())
                && java.util.Objects.equals(lowerBound(), other.lowerBound());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(upperBound(), lowerBound());
        }

        @Override
        public String toString() {
            Type lower = lowerBound();
            return lower != null ? "? super " + lower : "? extends " + upperBound();
        }
    }
}
