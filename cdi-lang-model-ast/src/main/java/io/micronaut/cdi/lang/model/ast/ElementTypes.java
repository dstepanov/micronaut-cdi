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
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.VisitorContext;
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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Reads a Micronaut type as the type of the language model a build compatible extension is written against.
 *
 * <p>A Micronaut {@link ClassElement} built for a use of a type keeps the annotations written on that use in its
 * {@link ClassElement#getTypeAnnotationMetadata() type annotations}, a type variable and a wildcard keep theirs
 * in their {@link io.micronaut.inject.ast.GenericElement#getGenericTypeAnnotationMetadata() generic type
 * annotations}, and every type argument, bound, super type and thrown type is a use of its own. That is what is
 * read here, in whichever language the compilation is in. Array dimensions are read individually as
 * {@link ClassElement#fromArray()} walks their component types.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementTypes {

    private static final String OBJECT = "java.lang.Object";

    private ElementTypes() {
    }

    /**
     * The type of the language model that the given Micronaut type is, carrying the annotations written on the
     * use of it that Micronaut recorded.
     *
     * @param element The Micronaut type
     * @param context The compilation the type is read in
     * @return The type
     */
    public static Type of(ClassElement element, VisitorContext context) {
        return of(element, null, context);
    }

    /**
     * The type of the language model that the given Micronaut type is, carrying the annotations written on the
     * use of it that Micronaut recorded, less the {@code NonNull} Micronaut itself writes on a type declared in
     * a null-marked scope.
     *
     * @param element    The Micronaut type
     * @param declaringType Whether the declaration the type belongs to is in a {@code NullMarked} class or package
     * @param context    The compilation the type is read in
     * @return The type
     */
    static Type of(ClassElement element, @Nullable ClassElement declaringType, VisitorContext context) {
        if (element.isVoid()) {
            return new Void(context);
        }
        if (element.isArray()) {
            return new Array(of(element.fromArray(), declaringType, context),
                typeAnnotationsOf(element, declaringType, context), context);
        }
        if (element.isPrimitive()) {
            return new Primitive(element.getName(), typeAnnotationsOf(element, declaringType, context), context);
        }
        if (element instanceof WildcardElement wildcard) {
            return wildcardOf(wildcard, declaringType, context);
        }
        if (element instanceof GenericPlaceholderElement placeholder) {
            return variableOf(placeholder, declaringType, context);
        }
        return classOf(element, typeAnnotationsOf(element, declaringType, context), declaringType, context);
    }

    /**
     * A class or parameterized type naming the given class, with the given annotations on the use of it.
     *
     * @param element     The class
     * @param annotations The annotations of the use
     * @param context     The compilation the type is read in
     * @return The type
     */
    static Type classOf(ClassElement element, List<AnnotationInfo> annotations, VisitorContext context) {
        return classOf(element, annotations, null, context);
    }

    private static Type classOf(ClassElement element, List<AnnotationInfo> annotations,
                                @Nullable ClassElement declaringType, VisitorContext context) {
        Class raw = new Class(element, annotations, context);
        // a generic class named without its arguments is a class type, not a parameterized one; Micronaut fills
        // the arguments of a raw use in from the declaration's bounds, so the raw use has to be asked about
        if (element.isRawType() || element.getTypeArguments().isEmpty()) {
            return raw;
        }
        List<Type> arguments = new ArrayList<>(element.getTypeArguments().size());
        for (Map.Entry<String, ClassElement> argument : element.getTypeArguments().entrySet()) {
            arguments.add(of(argument.getValue(), declaringType, context));
        }
        return new Parameterized(raw, List.copyOf(arguments), annotations, context);
    }

    /**
     * The class type naming the given class without its type arguments, with the given annotations on the use.
     *
     * @param element     The class
     * @param annotations The annotations of the use
     * @param context     The compilation the type is read in
     * @return The type
     */
    public static ClassType rawClassOf(ClassElement element, List<AnnotationInfo> annotations,
                                       VisitorContext context) {
        return new Class(element, annotations, context);
    }

    /**
     * The type variable a Micronaut placeholder stands for, where it is declared or where a type names it.
     *
     * @param placeholder The placeholder
     * @param context     The compilation the variable is read in
     * @return The variable
     */
    static TypeVariable variableOf(GenericPlaceholderElement placeholder, VisitorContext context) {
        return variableOf(placeholder, null, context);
    }

    private static TypeVariable variableOf(GenericPlaceholderElement placeholder, @Nullable ClassElement declaringType,
                                           VisitorContext context) {
        List<Type> bounds = new ArrayList<>(placeholder.getBounds().size());
        for (ClassElement bound : placeholder.getBounds()) {
            bounds.add(of(bound, declaringType, context));
        }
        return new Variable(placeholder, List.copyOf(bounds),
            annotationsIn(placeholder.getGenericTypeAnnotationMetadata(), declaringType, context), context);
    }

    private static WildcardType wildcardOf(WildcardElement wildcard, @Nullable ClassElement declaringType,
                                           VisitorContext context) {
        List<AnnotationInfo> annotations =
            annotationsIn(wildcard.getGenericTypeAnnotationMetadata(), declaringType, context);
        if (wildcard.hasExplicitLowerBound()) {
            return new Wildcard(null, of(wildcard.getLowerBounds().get(0), declaringType, context), annotations,
                context);
        }
        if (wildcard.hasExplicitUpperBound()) {
            return new Wildcard(of(wildcard.getUpperBounds().get(0), declaringType, context), null, annotations,
                context);
        }
        // the unbounded wildcard is the one bounded above by java.lang.Object, which is what it means and how
        // the language model reports it; Micronaut substitutes the declared bound of the type parameter, which
        // the source did not write
        return new Wildcard(objectType(context), null, annotations, context);
    }

    /**
     * Composes a wildcard with an upper bound, including Object for an unbounded wildcard.
     *
     * @param bound   The upper bound
     * @param context The compilation the wildcard is composed in
     * @return The wildcard
     */
    public static WildcardType wildcardWithUpperBound(Type bound, VisitorContext context) {
        return new Wildcard(Objects.requireNonNull(bound), null, List.of(), context);
    }

    /**
     * Composes a wildcard with a lower bound.
     *
     * @param bound   The lower bound
     * @param context The compilation the wildcard is composed in
     * @return The wildcard
     */
    public static WildcardType wildcardWithLowerBound(Type bound, VisitorContext context) {
        return new Wildcard(null, Objects.requireNonNull(bound), List.of(), context);
    }

    /**
     * The class type {@code java.lang.Object} with nothing written on it.
     *
     * @param context The compilation to look {@code java.lang.Object} up in
     * @return The type
     */
    public static ClassType objectType(VisitorContext context) {
        ClassElement object = ExtensionAnnotationTypes.declarationOf(OBJECT, context);
        return new Class(object != null ? object : ClassElement.of(Object.class), List.of(), context);
    }

    /**
     * The type of the language model for a class the compilation names, which is what an annotation member
     * naming a class is read as.
     *
     * @param name    The binary name, or the name of a primitive type
     * @param context The compilation to look the class up in
     * @return The type
     */
    static Type ofName(String name, VisitorContext context) {
        if ("void".equals(name)) {
            return new Void(context);
        }
        // an array class is named as the class file names it, [Ljava.util.Map; or [I, or as the source does
        if (name.startsWith("[")) {
            return new Array(ofName(componentOf(name.substring(1)), context), List.of(), context);
        }
        if (name.endsWith("[]")) {
            return new Array(ofName(name.substring(0, name.length() - 2), context), List.of(), context);
        }
        if (ClassUtils.getPrimitiveType(name).isPresent()) {
            return new Primitive(name, List.of(), context);
        }
        ClassElement element = ExtensionAnnotationTypes.declarationOf(name, context);
        if (element == null) {
            throw new IllegalStateException("The type " + name + " is not on the compilation's classpath");
        }
        return classLiteralOf(element, context);
    }

    /**
     * The name of the component an array class name without its first bracket names, in the form {@link #ofName}
     * reads.
     */
    private static String componentOf(String descriptor) {
        if (descriptor.startsWith("[")) {
            return descriptor;
        }
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return switch (descriptor) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> descriptor;
        };
    }

    /**
     * A class literal: the class without its type arguments, a generic class named as it is written in
     * {@code List.class}, and an array of one element by element, never a parameterized type.
     */
    private static Type classLiteralOf(ClassElement element, VisitorContext context) {
        if (element.isArray()) {
            return new Array(classLiteralOf(element.fromArray(), context), List.of(), context);
        }
        if (element.isPrimitive()) {
            return new Primitive(element.getName(), List.of(), context);
        }
        return rawClassOf(element, List.of(), context);
    }

    /**
     * The Micronaut element a type of this model stands for: what {@link #of(ClassElement, VisitorContext)} was
     * given, so that a type handed back by a builder can be composed with rather than only read.
     *
     * @param type The type
     * @return The element
     */
    public static ClassElement elementOf(Type type) {
        if (type instanceof Void) {
            return PrimitiveElement.VOID;
        }
        if (type instanceof Primitive primitive) {
            return PrimitiveElement.valueOf(primitive.name());
        }
        if (type instanceof Array array) {
            return elementOf(array.componentType()).toArray();
        }
        if (type instanceof Class classType) {
            return classType.element;
        }
        if (type instanceof Parameterized parameterized) {
            return ((Class) parameterized.genericClass()).element;
        }
        if (type instanceof Wildcard wildcard) {
            Type upper = wildcard.upperBound();
            Type lower = wildcard.lowerBound();
            if (upper != null && lower == null && OBJECT.equals(elementOf(upper).getName())) {
                // ? extends Object as it was written, which Micronaut's own composed wildcard takes for the bound
                // of an unbounded one
                return new ExplicitObjectWildcard(elementOf(upper));
            }
            return WildcardElement.of(List.of(elementOf(upper == null ? objectType(wildcard.context) : upper)),
                lower == null ? List.of() : List.of(elementOf(lower)));
        }
        if (type instanceof Variable variable) {
            return variable.element;
        }
        throw new IllegalArgumentException("The type " + type + " was not composed by this model");
    }

    /**
     * The annotations Micronaut recorded on a use of a type, retained until runtime only, less what Micronaut
     * wrote there itself: the annotations of its own packages, and the {@code NonNull} it writes on a type
     * declared in a null-marked scope.
     *
     * @param use        The type, as used somewhere
     * @param declaringType Whether the declaration the type belongs to is in a {@code NullMarked} class or package
     * @param context    The compilation the use is read in
     * @return The annotations
     */
    static List<AnnotationInfo> typeAnnotationsOf(ClassElement use, @Nullable ClassElement declaringType,
                                                 VisitorContext context) {
        return annotationsIn(use.getTypeAnnotationMetadata(), declaringType, context);
    }

    private static List<AnnotationInfo> annotationsIn(AnnotationMetadata metadata, @Nullable ClassElement declaringType,
                                                      VisitorContext context) {
        List<AnnotationInfo> found = new ArrayList<>();
        for (String name : metadata.getDeclaredAnnotationNames()) {
            if (!ExtensionAnnotations.isReportedOnType(name, declaringType, context)) {
                continue;
            }
            AnnotationValue<Annotation> annotation = metadata.getDeclaredAnnotation(name);
            if (annotation != null) {
                found.add(new ElementAnnotationInfo(annotation, context));
            }
        }
        return ExtensionAnnotations.unfoldSingleRepetitions(found, metadata);
    }

    /**
     * A type with the annotations written on the use of it.
     */
    private abstract static class Annotated implements Type {

        final VisitorContext context;
        private final List<AnnotationInfo> annotations;

        Annotated(List<AnnotationInfo> annotations, VisitorContext context) {
            // the model is immutable, whatever list the annotations were collected into
            this.annotations = List.copyOf(annotations);
            this.context = context;
        }

        @Override
        public final boolean hasAnnotation(java.lang.Class<? extends Annotation> annotationType) {
            return annotation(annotationType) != null;
        }

        @Override
        public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return annotations.stream().anyMatch(predicate);
        }

        @Override
        public final <T extends Annotation> @Nullable AnnotationInfo annotation(
            java.lang.Class<T> annotationType) {
            for (AnnotationInfo annotation : annotations) {
                if (annotation.name().equals(annotationType.getName())) {
                    return annotation;
                }
            }
            return null;
        }

        @Override
        public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(
            java.lang.Class<T> annotationType) {
            return ExtensionAnnotations.repeatableIn(annotations, annotationType.getName(), context);
        }

        @Override
        public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
            return annotations.stream().filter(predicate).toList();
        }

        @Override
        public final Collection<AnnotationInfo> annotations() {
            return annotations;
        }
    }

    /**
     * The type {@code void}, which carries no annotation of its own: an annotation written on a method returning
     * {@code void} is written on the method.
     */
    private static final class Void extends Annotated implements VoidType {

        private Void(VisitorContext context) {
            super(List.of(), context);
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
     * A primitive type, with the annotations written on the use of it (Micronaut Core 5.3 records them on the
     * copy of the shared primitive element it hands out for an annotated use).
     */
    private static final class Primitive extends Annotated implements PrimitiveType {

        private final String name;

        private Primitive(String name, List<AnnotationInfo> annotations, VisitorContext context) {
            super(annotations, context);
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
            return name;
        }
    }

    /**
     * An array type.
     */
    private static final class Array extends Annotated implements ArrayType {

        private final Type componentType;

        private Array(Type componentType, List<AnnotationInfo> annotations, VisitorContext context) {
            super(annotations, context);
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

        private final ClassElement element;

        private Class(ClassElement element, List<AnnotationInfo> annotations, VisitorContext context) {
            super(annotations, context);
            this.element = element;
        }

        @Override
        public ClassInfo declaration() {
            return ElementClassInfo.declarationOf(element, context);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ClassType other && element.getName().equals(other.declaration().name());
        }

        @Override
        public int hashCode() {
            return element.getName().hashCode();
        }

        @Override
        public String toString() {
            return element.getName();
        }
    }

    /**
     * A class type named with its type arguments.
     */
    private static final class Parameterized extends Annotated implements ParameterizedType {

        private final ClassType genericClass;
        private final List<Type> typeArguments;

        private Parameterized(ClassType genericClass, List<Type> typeArguments, List<AnnotationInfo> annotations,
                              VisitorContext context) {
            super(annotations, context);
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

        private final GenericPlaceholderElement element;
        private final String name;
        private final List<Type> bounds;

        private Variable(GenericPlaceholderElement element, List<Type> bounds, List<AnnotationInfo> annotations,
                         VisitorContext context) {
            super(annotations, context);
            this.element = element;
            this.name = element.getVariableName();
            this.bounds = bounds;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Type> bounds() {
            return bounds;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TypeVariable other && name.equals(other.name());
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
     * A wildcard composed as {@code ? extends Object}, whose upper bound was written: Micronaut's own composed
     * wildcard ({@link WildcardElement#of}) takes an upper bound of {@code Object} for the implicit one of an
     * unbounded wildcard.
     *
     * @param upper The upper bound, Object
     */
    private record ExplicitObjectWildcard(ClassElement upper) implements WildcardElement {
        @Override
        public List<? extends ClassElement> getUpperBounds() {
            return List.of(upper);
        }

        @Override
        public List<? extends ClassElement> getLowerBounds() {
            return List.of();
        }

        @Override
        public boolean hasExplicitUpperBound() {
            return true;
        }

        @Override
        public String getName() {
            return upper.getName();
        }

        @Override
        public boolean isAssignable(String type) {
            return upper.isAssignable(type);
        }

        @Override
        public boolean isProtected() {
            return upper.isProtected();
        }

        @Override
        public boolean isPublic() {
            return upper.isPublic();
        }

        @Override
        public Object getNativeType() {
            return upper.getNativeType();
        }

        @Override
        public Object getGenericNativeType() {
            return this;
        }

        @Override
        public ClassElement toArray() {
            throw new IllegalArgumentException("A wildcard cannot be an array component");
        }

        @Override
        public ClassElement fromArray() {
            throw new IllegalArgumentException("A wildcard is not an array");
        }
    }

    /**
     * A wildcard type.
     */
    private static final class Wildcard extends Annotated implements WildcardType {

        private final @Nullable Type upperBound;
        private final @Nullable Type lowerBound;

        private Wildcard(@Nullable Type upperBound, @Nullable Type lowerBound, List<AnnotationInfo> annotations,
                         VisitorContext context) {
            super(annotations, context);
            this.upperBound = upperBound;
            this.lowerBound = lowerBound;
        }

        @Override
        public @Nullable Type upperBound() {
            return upperBound;
        }

        @Override
        public @Nullable Type lowerBound() {
            return lowerBound;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WildcardType other && Objects.equals(upperBound, other.upperBound())
                && Objects.equals(lowerBound, other.lowerBound());
        }

        @Override
        public int hashCode() {
            return Objects.hash(upperBound, lowerBound);
        }

        @Override
        public String toString() {
            return lowerBound != null ? "? super " + lowerBound : "? extends " + upperBound;
        }
    }
}
