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
import io.micronaut.core.type.WildcardArgument;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The boundary between the {@link Type} the interfaces of the specification are written in and the
 * {@link Argument} the container works with.
 *
 * <p>A type a program hands in - to a lookup, to the selection of an event, to the matching the bean container
 * offers - is read here, once, into the argument that describes it. Nothing else in the container reads a
 * {@code Type}, and nothing here reads a class back: a type that was handed in carries its arguments and bounds, and
 * asking for them resolves nothing. A type the container hands out - a bean type, an observed type, the type of an
 * injection point or of an event - is the one Micronaut renders the argument it is held as into
 * ({@link Argument#asType()}), exactly as the declaration wrote it and equal to the type the JDK reflects.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class SpecificationTypes {

    private SpecificationTypes() {
    }

    /**
     * The argument that describes a type that was handed in: a class, a parameterized type, a wildcard or a
     * type variable with its bounds, and a generic array. An argument is itself a type, and is returned as it is.
     *
     * @param type The type
     * @return The argument
     * @throws IllegalArgumentException For a type that is none of those
     */
    public static Argument<?> argumentOf(Type type) {
        return argumentOf(type, Map.of());
    }

    /**
     * The arguments that describe the given types, in order.
     *
     * @param types The types
     * @return The arguments
     */
    public static List<Argument<?>> argumentsOf(Iterable<? extends Type> types) {
        List<Argument<?>> arguments = new java.util.ArrayList<>();
        for (Type type : types) {
            arguments.add(argumentOf(type));
        }
        return arguments;
    }

    private static Argument<?> argumentOf(Type type, Map<String, Class<?>> converting) {
        if (type instanceof Argument<?> argument) {
            return argument;
        }
        if (type instanceof Class<?> aClass) {
            return Argument.of(aClass);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return Argument.of(raw, (String) null, argumentsOf(parameterized.getActualTypeArguments(), converting));
        }
        if (type instanceof WildcardType wildcard) {
            Argument<?>[] uppers = argumentsOf(wildcard.getUpperBounds(), converting);
            Argument<?>[] lowers = argumentsOf(wildcard.getLowerBounds(), converting);
            Argument<?> first = uppers.length == 0 ? Argument.OBJECT_ARGUMENT : uppers[0];
            return Argument.ofWildcard(first.getType(), null, null, first.getTypeParameters(), uppers, lowers);
        }
        if (type instanceof TypeVariable<?> variable) {
            String name = variable.getName();
            Class<?> erasure = converting.get(name);
            if (erasure != null) {
                // the variable named again within its own bounds, the T of Comparable<T>: the bounds end there
                return Argument.ofTypeVariable(erasure, null, name);
            }
            erasure = erasureOf(variable, 0);
            Map<String, Class<?>> within = new HashMap<>(converting);
            within.put(name, erasure);
            Argument<?>[] bounds = argumentsOf(variable.getBounds(), within);
            Argument<?> first = bounds.length == 0 ? Argument.OBJECT_ARGUMENT : bounds[0];
            return Argument.ofTypeVariable(erasure, null, name, null, first.getTypeParameters(),
                bounds.length == 0 ? new Argument<?>[] {first} : bounds);
        }
        if (type instanceof GenericArrayType array) {
            return argumentOf(array.getGenericComponentType(), converting).arrayType();
        }
        throw new IllegalArgumentException("The type " + type + " is not a class, a parameterized type, a wildcard, "
            + "a type variable or an array of one");
    }

    /**
     * The argument a bean is looked up by for the given type: a class, a parameterized type of them, or an array
     * of either.
     *
     * @param type The type
     * @param <T>  The type
     * @return The argument
     * @throws IllegalArgumentException For a type with a wildcard or a type variable in it
     */
    @SuppressWarnings("unchecked")
    public static <T> Argument<T> lookupArgumentOf(Type type) {
        if (type instanceof Class<?> aClass) {
            return (Argument<T>) Argument.of(aClass);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Argument<?>[] resolved = new Argument<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = lookupArgumentOf(arguments[i]);
            }
            return (Argument<T>) Argument.of((Class<?>) parameterized.getRawType(), resolved);
        }
        if (type instanceof GenericArrayType array) {
            // an array of a parameterized type keeps the arguments of its component (section 2.1.2.1), which is
            // a lookup type by the same rules
            return (Argument<T>) lookupArgumentOf(array.getGenericComponentType()).arrayType();
        }
        throw new IllegalArgumentException("A bean cannot be looked up by the type " + type + ": only a class, "
            + "a parameterized type and an array of one describe a bean");
    }

    private static Argument<?>[] argumentsOf(Type[] types, Map<String, Class<?>> converting) {
        Argument<?>[] arguments = new Argument<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            arguments[i] = argumentOf(types[i], converting);
        }
        return arguments;
    }

    /**
     * The class a type erases to, without describing it: the first bound of a variable or of a wildcard, all
     * the way up.
     */
    private static Class<?> erasureOf(Type type, int depth) {
        if (depth > 32) {
            return Object.class;
        }
        if (type instanceof Argument<?> argument) {
            return argument.getType();
        }
        if (type instanceof Class<?> aClass) {
            return aClass;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : erasureOf(bounds[0], depth + 1);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] bounds = wildcard.getUpperBounds();
            return bounds.length == 0 ? Object.class : erasureOf(bounds[0], depth + 1);
        }
        if (type instanceof GenericArrayType array) {
            return erasureOf(array.getGenericComponentType(), depth + 1).arrayType();
        }
        return Object.class;
    }

    /**
     * The raw class of a type that was handed in, or {@code null} for one that has none of its own: a wildcard
     * or a type variable.
     *
     * @param type The type
     * @return The raw class
     */
    public static @Nullable Class<?> rawClassOf(Type type) {
        if (type instanceof Argument<?> argument) {
            boolean unresolved = argument instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved();
            return argument instanceof WildcardArgument<?> || unresolved ? null : argument.getType();
        }
        if (type instanceof Class<?> aClass) {
            return aClass;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType array) {
            // the raw class of an array of a parameterized type is the array of the raw component
            Class<?> component = rawClassOf(array.getGenericComponentType());
            return component == null ? null : component.arrayType();
        }
        return null;
    }
}
