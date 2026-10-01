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
package io.micronaut.cdi.internal.type;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A parameterized type handed out where the specification reports one.
 *
 * <p>It equals what reflection makes, both ways, because it compares the same three parts the language's own
 * implementation compares: the raw type, the owner, and the arguments.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class ParameterizedValue implements ParameterizedType {

    private final Class<?> rawType;
    private final Type[] arguments;

    ParameterizedValue(Class<?> rawType, Type[] arguments) {
        this.rawType = rawType;
        this.arguments = arguments;
    }

    @Override
    public Type[] getActualTypeArguments() {
        return arguments.clone();
    }

    @Override
    public Type getRawType() {
        return rawType;
    }

    @Override
    public @Nullable Type getOwnerType() {
        return rawType.getDeclaringClass();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ParameterizedType other)) {
            return false;
        }
        return rawType.equals(other.getRawType())
            && Objects.equals(rawType.getDeclaringClass(), other.getOwnerType())
            && Arrays.equals(arguments, other.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(arguments) ^ Objects.hashCode(rawType.getDeclaringClass()) ^ rawType.hashCode();
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", rawType.getName() + "<", ">");
        for (Type argument : arguments) {
            joiner.add(argument.getTypeName());
        }
        return joiner.toString();
    }
}
