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
            return ofArray(of(clazz.getComponentType()), 1);
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
        throw new UnsupportedOperationException("An array type is not composed here yet");
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Class<?>... typeArguments) {
        throw new UnsupportedOperationException("A parameterized type is not composed here yet");
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Type... typeArguments) {
        throw new UnsupportedOperationException("A parameterized type is not composed here yet");
    }

    @Override
    public ParameterizedType parameterized(ClassType genericType, Type... typeArguments) {
        throw new UnsupportedOperationException("A parameterized type is not composed here yet");
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
        return ElementTypes.of(element);
    }
}
