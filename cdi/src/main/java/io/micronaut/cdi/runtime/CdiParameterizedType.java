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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A parameterized type made rather than found: the bean type of a generic class.
 *
 * <p>The rest of a bean's type hierarchy comes back from reflection already parameterized — a generic superclass
 * or interface is a {@link ParameterizedType} as the language reports it. The one type reflection has no
 * parameterized form for is the class itself: a generic bean class is, as a bean type, the class with its own
 * type variables, which is what this makes.</p>
 *
 * <p>It equals what reflection makes, both ways, because it compares the same three parts the language's own
 * implementation compares: the raw type, the owner, and the arguments.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiParameterizedType implements ParameterizedType {

    private final Class<?> rawType;
    private final Type[] arguments;

    private CdiParameterizedType(Class<?> rawType, Type[] arguments) {
        this.rawType = rawType;
        this.arguments = arguments;
    }

    /**
     * The given class as the bean type it is: itself when it declares no type parameters, and the parameterized
     * form over its own type variables when it does.
     *
     * @param type The class
     * @return The bean type
     */
    public static Type of(Class<?> type) {
        if (type.getTypeParameters().length == 0) {
            return type;
        }
        return new CdiParameterizedType(type, type.getTypeParameters());
    }

    /**
     * The parameterized form of the given class over the given arguments.
     *
     * @param type      The raw class
     * @param arguments The type arguments
     * @return The parameterized type
     */
    public static Type of(Class<?> type, Type[] arguments) {
        if (arguments.length == 0) {
            return type;
        }
        return new CdiParameterizedType(type, arguments);
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
            && Objects.equals(getOwnerType(), other.getOwnerType())
            && Arrays.equals(arguments, other.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(arguments) ^ Objects.hashCode(getOwnerType()) ^ rawType.hashCode();
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
