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
 * offers - is read here, once, into the argument that describes it. A type the container hands out - a bean
 * type, an observed type, the type of an injection point or of an event - is made here from the argument it is
 * held as. Nothing else in the container reads a {@code Type} or makes one, and nothing here reads a class
 * back: a type that was handed in carries its arguments and bounds, and asking for them resolves nothing.</p>
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
            return arrayOf(argumentOf(array.getGenericComponentType(), converting));
        }
        throw new IllegalArgumentException("The type " + type + " is not a class, a parameterized type, a wildcard, "
            + "a type variable or an array of one");
    }

    /**
     * The argument a bean is looked up by for the given type: a class, or a parameterized type of them.
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
        throw new IllegalArgumentException("A bean cannot be looked up by the type " + type + ": only a class and "
            + "a parameterized type describe a bean");
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
     * The argument of an array of what the given argument describes: an array has the type arguments of its
     * component, and an array of a variable is the variable's placeholder over the array of its erasure.
     *
     * @param component The component
     * @return The array
     */
    public static Argument<?> arrayOf(Argument<?> component) {
        Class<?> arrayClass = component.getType().arrayType();
        if (component instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved()) {
            return Argument.ofTypeVariable(arrayClass, null, placeholder.getVariableName(), null,
                placeholder.getTypeParameters(), placeholder.getBounds().toArray(Argument.ZERO_ARGUMENTS));
        }
        if (component.isRawType() || component.getTypeParameters().length == 0) {
            return Argument.of(arrayClass);
        }
        return Argument.of(arrayClass, (String) null, component.getTypeParameters());
    }

    /**
     * The type an argument describes, the way the declaration wrote it: a type variable left unresolved is the
     * variable with its bounds, a wildcard keeps its bounds, a raw type is the class, and an array of a
     * parameterized type or of a variable is a generic array. A type resolved in place of a variable is that type.
     *
     * @param argument The argument
     * @return The type
     */
    public static Type typeOf(Argument<?> argument) {
        return typeOf(argument, Map.of());
    }

    /**
     * The types the given arguments describe, in order.
     *
     * @param arguments The arguments
     * @return The types
     */
    public static List<Type> typesOf(Iterable<? extends Argument<?>> arguments) {
        List<Type> types = new java.util.ArrayList<>();
        for (Argument<?> argument : arguments) {
            types.add(typeOf(argument));
        }
        return types;
    }

    /**
     * The type an argument describes, inside the bounds of the given variables: a variable named again within
     * its own bounds is that variable.
     */
    private static Type typeOf(Argument<?> argument, Map<String, TypeVariableValue> bounding) {
        if (argument instanceof WildcardArgument<?> wildcard) {
            return new WildcardValue(typesOf(wildcard.getUpperBounds(), bounding),
                typesOf(wildcard.getLowerBounds(), bounding));
        }
        Class<?> type = argument.getType();
        if (argument instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved()) {
            String name = placeholder.getVariableName();
            TypeVariableValue variable = bounding.get(name);
            if (variable == null) {
                // created before its bounds, which may name it
                variable = new TypeVariableValue(name, new Type[0]);
                Map<String, TypeVariableValue> within = new HashMap<>(bounding);
                within.put(name, variable);
                variable.bounds(typesOf(placeholder.getBounds(), within));
            }
            // the placeholder of an array of a variable is an array of the variable
            return arrayOf(variable, type);
        }
        Argument<?>[] typeParameters = argument.getTypeParameters();
        if (typeParameters.length == 0 || argument.isRawType()) {
            return type;
        }
        Type[] arguments = new Type[typeParameters.length];
        for (int i = 0; i < typeParameters.length; i++) {
            arguments[i] = typeOf(typeParameters[i], bounding);
        }
        Class<?> component = type;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        // an array has the type arguments of its component
        return arrayOf(new ParameterizedValue(component, arguments), type);
    }

    private static Type[] typesOf(List<Argument<?>> arguments, Map<String, TypeVariableValue> bounding) {
        Type[] types = new Type[arguments.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = typeOf(arguments.get(i), bounding);
        }
        return types;
    }

    /**
     * The component wrapped in as many generic array levels as the class has dimensions.
     */
    private static Type arrayOf(Type component, Class<?> type) {
        Type result = component;
        for (Class<?> level = type; level.isArray(); level = level.getComponentType()) {
            result = new GenericArrayValue(result);
        }
        return result;
    }

    /**
     * The parameterized form of the given class over the given arguments, or the class where there are none.
     *
     * @param type      The raw class
     * @param arguments The type arguments
     * @return The parameterized type
     */
    public static Type parameterized(Class<?> type, Type[] arguments) {
        return arguments.length == 0 ? type : new ParameterizedValue(type, arguments);
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
            return argument instanceof WildcardArgument<?>
                || argument instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved()
                ? null : argument.getType();
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
