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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.VoidType;
import jakarta.inject.Singleton;

/**
 * The {@code Types} a synthesis method asks for, answering reflectively: the synthesis phase of section 2.10.5
 * runs while the container starts, where the classes are real.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ReflectiveTypesFactory implements Types {

    private final BeanContext beanContext;

    public ReflectiveTypesFactory(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Type of(Class<?> clazz) {
        return ReflectionTypes.of(clazz);
    }

    @Override
    public VoidType ofVoid() {
        return (VoidType) ReflectionTypes.of(void.class);
    }

    @Override
    public PrimitiveType ofPrimitive(PrimitiveType.PrimitiveKind kind) {
        return (PrimitiveType) ReflectionTypes.of(switch (kind) {
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case SHORT -> short.class;
            case INT -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
            case CHAR -> char.class;
        });
    }

    @Override
    public ClassType ofClass(String name) {
        ClassLoader classLoader = beanContext.getClassLoader() != null
            ? beanContext.getClassLoader() : ReflectiveTypesFactory.class.getClassLoader();
        try {
            return (ClassType) ReflectionTypes.of(Class.forName(name, false, classLoader));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The class " + name + " is not on the deployment's classpath", e);
        }
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
        return (ParameterizedType) ReflectionTypes.of(
            io.micronaut.cdi.runtime.CdiParameterizedType.of(genericType, typeArguments));
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
    public jakarta.enterprise.lang.model.types.WildcardType wildcardWithUpperBound(Type upperBound) {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }

    @Override
    public jakarta.enterprise.lang.model.types.WildcardType wildcardWithLowerBound(Type lowerBound) {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }

    @Override
    public jakarta.enterprise.lang.model.types.WildcardType wildcardUnbounded() {
        throw new UnsupportedOperationException("A wildcard type is not composed here yet");
    }
}
