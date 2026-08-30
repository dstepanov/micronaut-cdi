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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.cdi.runtime.CdiParameterizedType;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;

/**
 * Reads a type of the language model back as the reflected type it describes, which is how a type an extension
 * composed reaches the machinery that works with reflected types.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class LangModelTypes {

    private LangModelTypes() {
    }

    /**
     * The reflected type the given type of the language model describes.
     *
     * @param type        The type of the language model
     * @param classLoader Where its classes are loaded from
     * @return The reflected type
     */
    static java.lang.reflect.Type reflectiveOf(Type type, ClassLoader classLoader) {
        if (type instanceof ClassType classType) {
            return classOf(classType.declaration().name(), classLoader);
        }
        if (type instanceof ParameterizedType parameterized) {
            java.lang.reflect.Type raw = reflectiveOf(parameterized.genericClass(), classLoader);
            java.lang.reflect.Type[] arguments = new java.lang.reflect.Type[parameterized.typeArguments().size()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = reflectiveOf(parameterized.typeArguments().get(i), classLoader);
            }
            return CdiParameterizedType.of((Class<?>) raw, arguments);
        }
        if (type instanceof ArrayType array) {
            java.lang.reflect.Type component = reflectiveOf(array.componentType(), classLoader);
            if (component instanceof Class<?> aClass) {
                return java.lang.reflect.Array.newInstance(aClass, 0).getClass();
            }
        }
        throw new IllegalArgumentException("The type " + type + " cannot be read back as a reflected type here");
    }

    private static Class<?> classOf(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The class " + name + " is not on the deployment's classpath", e);
        }
    }
}
