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
package io.micronaut.cdi.runtime.type;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;

/**
 * An array of a parameterized type or of a type variable, handed out where the specification reports one.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class GenericArrayValue implements GenericArrayType {

    private final Type component;

    GenericArrayValue(Type component) {
        this.component = component;
    }

    @Override
    public Type getGenericComponentType() {
        return component;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GenericArrayType other && component.equals(other.getGenericComponentType());
    }

    @Override
    public int hashCode() {
        return component.hashCode();
    }

    @Override
    public String toString() {
        return component.getTypeName() + "[]";
    }
}
