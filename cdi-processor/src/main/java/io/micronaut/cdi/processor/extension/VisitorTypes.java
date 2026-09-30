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
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.VoidType;
import jakarta.enterprise.lang.model.types.WildcardType;

/**
 * The {@code Types} an extension method asks for, answering from what the compiler can see.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class VisitorTypes implements Types {

    private final VisitorContext context;

    VisitorTypes(VisitorContext context) {
        this.context = context;
    }

    @Override
    public Type of(Class<?> clazz) {
        if (clazz == void.class) {
            return ofVoid();
        }
        if (clazz.isArray()) {
            int dimensions = 0;
            Class<?> component = clazz;
            while (component.isArray()) {
                dimensions++;
                component = component.getComponentType();
            }
            return ofArray(of(component), dimensions);
        }
        if (clazz.isPrimitive()) {
            return ElementTypes.of(io.micronaut.inject.ast.PrimitiveElement.valueOf(clazz.getName()));
        }
        return element(clazz.getName());
    }

    @Override
    public VoidType ofVoid() {
        return (VoidType) ElementTypes.of(ClassElement.of(void.class));
    }

    @Override
    public PrimitiveType ofPrimitive(PrimitiveType.PrimitiveKind kind) {
        return (PrimitiveType) ElementTypes.of(ClassElement.of(switch (kind) {
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case SHORT -> short.class;
            case INT -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
            case CHAR -> char.class;
        }));
    }

    @Override
    public ClassType ofClass(String name) {
        return (ClassType) element(name);
    }

    @Override
    public ClassType ofClass(ClassInfo clazz) {
        return ofClass(clazz.name());
    }

    @Override
    public ArrayType ofArray(Type componentType, int dimensions) {
        // composed from the component's own element, raised a dimension at a time: a primitive, a class and
        // an array all have one, and none of them survives a round trip through a name
        ClassElement component = ElementTypes.elementOf(componentType);
        for (int i = 0; i < dimensions; i++) {
            component = component.toArray();
        }
        return (ArrayType) ElementTypes.of(component);
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Class<?>... typeArguments) {
        Type[] arguments = new Type[typeArguments.length];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = of(typeArguments[i]);
        }
        return parameterized(genericType, arguments);
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Type... typeArguments) {
        return parameterized(ofClass(genericType.getName()), typeArguments);
    }

    @Override
    public ParameterizedType parameterized(ClassType genericType, Type... typeArguments) {
        // composed from the generic class's own element, given the elements of the arguments
        java.util.List<ClassElement> arguments = new java.util.ArrayList<>(typeArguments.length);
        for (Type typeArgument : typeArguments) {
            arguments.add(ElementTypes.elementOf(typeArgument));
        }
        ClassElement generic = ElementTypes.elementOf(genericType);
        if (generic.getTypeArguments().size() != arguments.size()) {
            throw new IllegalArgumentException("The class " + generic.getName() + " declares "
                + generic.getTypeArguments().size() + " type parameters, and was given " + arguments.size());
        }
        return (ParameterizedType) ElementTypes.of(generic.withTypeArguments(arguments));
    }

    @Override
    public WildcardType wildcardWithUpperBound(Type upperBound) {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }

    @Override
    public WildcardType wildcardWithLowerBound(Type lowerBound) {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }

    @Override
    public WildcardType wildcardUnbounded() {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }

    private Type element(String name) {
        ClassElement element = context.getClassElement(name).orElseThrow(() ->
            new IllegalArgumentException("The type " + name + " is not on the compilation's classpath"));
        // a class named by itself is the class type, whatever type parameters it declares: the arguments of a
        // parameterized type are given to parameterized
        return ElementTypes.rawClassOf(element, java.util.List.of());
    }
}
