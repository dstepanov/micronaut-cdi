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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The boundary between the {@link Type} the interfaces of the specification are written in and the
 * {@link Argument} the container works with.
 *
 * <p>A type a program hands in - to a lookup, to the selection of an event, to the matching the bean container
 * offers - is read into the argument that describes it by Micronaut ({@link Argument#of(Type)}), which calls only
 * the accessors of the {@code Type} interfaces and reads no class: a class, a parameterized type, a wildcard or a
 * type variable with its bounds, and a generic array. What is left here are the rules of the specification for the
 * type of a lookup. A type the container hands out - a bean type, an observed type, the type of an injection point
 * or of an event - is the one Micronaut renders the argument it is held as into ({@link Argument#asType()}), exactly
 * as the declaration wrote it and equal to the type the JDK reflects.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class SpecificationTypes {

    private SpecificationTypes() {
    }

    /**
     * The arguments that describe the given types, in order ({@link Argument#of(Type)}).
     *
     * @param types The types
     * @return The arguments
     */
    public static List<Argument<?>> argumentsOf(Iterable<? extends Type> types) {
        List<Argument<?>> arguments = new ArrayList<>();
        for (Type type : types) {
            arguments.add(Argument.of(type));
        }
        return arguments;
    }

    /**
     * The argument a bean is looked up by for the given type: a class, a parameterized type of them, or an array
     * of either.
     *
     * @param type The type
     * @param <T>  The type
     * @return The argument
     * @throws IllegalArgumentException For a type with a wildcard or a type variable in it, but for a wildcard argument
     *                                  of the built-in {@code Instance}, {@code Provider} and {@code Event}
     */
    @SuppressWarnings("unchecked")
    public static <T> Argument<T> lookupArgumentOf(Type type) {
        Argument<?> argument = Argument.of(type);
        if (!isLookupType(argument)) {
            throw new IllegalArgumentException("A bean cannot be looked up by the type " + type.getTypeName()
                + ": only a class, a parameterized type and an array of one describe a bean");
        }
        return (Argument<T>) argument;
    }

    /**
     * Whether an argument describes a type a bean is looked up by: neither a wildcard nor a type variable, and
     * none among its type arguments - an array keeps the type arguments of its component (section 2.1.2.1) - but
     * a wildcard argument of the built-in {@code Instance} and {@code Event} beans, which are looked up with a
     * wildcard as freely as they are injected with one (CDI 5.0, sections 5.6.1 and 9.2): what the wildcard stands
     * for is theirs to read, and one that names nothing is refused as they are built.
     */
    private static boolean isLookupType(Argument<?> argument) {
        // a variable, and an array of one, is a placeholder left unresolved
        if (argument.isWildcard() || argument instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved()) {
            return false;
        }
        boolean builtIn = isBuiltInLookup(argument.getType());
        for (Argument<?> typeArgument : argument.getTypeParameters()) {
            if (!(builtIn && typeArgument.isWildcard()) && !isLookupType(typeArgument)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBuiltInLookup(Class<?> raw) {
        return "jakarta.enterprise.inject.Instance".equals(raw.getName())
            || "jakarta.enterprise.event.Event".equals(raw.getName())
            || "jakarta.inject.Provider".equals(raw.getName());
    }
}
