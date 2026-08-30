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
import io.micronaut.core.type.Argument;

import java.lang.reflect.ParameterizedType;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import java.lang.reflect.Type;

/**
 * Reads a type of the reflection API as the Micronaut argument that describes it.
 *
 * <p>The specification asks for a bean by a {@link Type}, since that is what a program has to hand when it looks
 * one up itself. Micronaut resolves a bean by an {@link Argument}, which is the same thing described the way it
 * was compiled. A parameterized type is carried across with its arguments, so that a lookup of a parameterized
 * type resolves only the beans of that parameterization, which is what section 2.4.2 asks for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiTypes {

    /**
     * The primitive types and the classes that box them, each mapped to the other.
     */
    private static final Map<Class<?>, Class<?>> COUNTERPART = Map.ofEntries(
        Map.entry(boolean.class, Boolean.class), Map.entry(Boolean.class, boolean.class),
        Map.entry(byte.class, Byte.class), Map.entry(Byte.class, byte.class),
        Map.entry(char.class, Character.class), Map.entry(Character.class, char.class),
        Map.entry(short.class, Short.class), Map.entry(Short.class, short.class),
        Map.entry(int.class, Integer.class), Map.entry(Integer.class, int.class),
        Map.entry(long.class, Long.class), Map.entry(Long.class, long.class),
        Map.entry(float.class, Float.class), Map.entry(Float.class, float.class),
        Map.entry(double.class, Double.class), Map.entry(Double.class, double.class)
    );

    private CdiTypes() {
    }

    /**
     * The other of the primitive type and the class that boxes it.
     *
     * <p>Section 2.1.2 counts the two as one bean type, so a bean of either is resolved by a lookup of the
     * other. Micronaut resolves a bean by the type it was written as and keeps them apart, so a lookup here is
     * made twice and what the two resolve is put together.</p>
     *
     * @param type The type looked up
     * @param <T>  The type
     * @return The counterpart, or {@code null} when the type is neither a primitive nor a class that boxes one
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable Argument<T> counterpartOf(Argument<?> type) {
        if (type.getTypeParameters().length > 0) {
            return null;
        }
        Class<?> counterpart = COUNTERPART.get(type.getType());
        return counterpart == null ? null : (Argument<T>) Argument.of(counterpart);
    }

    /**
     * The raw class of a type, or {@code null} for one that has none of its own.
     *
     * @param type The type
     * @return The raw class
     */
    public static @Nullable Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> aClass) {
            return aClass;
        }
        if (type instanceof java.lang.reflect.ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    /**
     * The class that boxes a primitive, or the class itself when it is not one.
     *
     * @param type The class
     * @return The boxed form
     */
    public static Class<?> boxedOf(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        Class<?> boxed = COUNTERPART.get(type);
        return boxed == null ? type : boxed;
    }

    /**
     * The required type of the lookup, with any type argument the compiler recorded as a variable rebuilt as
     * one: the compiled argument erases a variable to its first bound, and the rules match a variable by every
     * bound it declared.
     *
     * @param beanType The compiled argument of the lookup
     * @return The required type
     */
    public static Type requiredTypeOf(Argument<?> beanType) {
        Type required = CdiTypes.typeOf(beanType);
        String[] recorded = beanType.getAnnotationMetadata()
            .stringValues("io.micronaut.cdi.annotation.CdiGenericVariables");
        return applyRecordedVariables(required, recorded, beanType.getType().getClassLoader());
    }

    /**
     * Rebuilds the variables and wildcards a compiled argument erased, from what was recorded about them while
     * the type was compiled.
     *
     * @param required    The type as the compiled argument has it
     * @param recorded    The recorded entries, position by position
     * @param classLoader Where the bounds' classes are loaded from
     * @return The type with its variables and wildcards back
     */
    static Type applyRecordedVariables(Type required, String[] recorded, @Nullable ClassLoader classLoader) {
        if (recorded.length == 0 || !(required instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return required;
        }
        Type[] arguments = parameterized.getActualTypeArguments().clone();
        for (String entry : recorded) {
            int split = entry.indexOf('=');
            int kindSplit = entry.indexOf(':', split);
            if (kindSplit < 0) {
                continue;
            }
            int position = Integer.parseInt(entry.substring(0, split));
            if (position >= arguments.length) {
                continue;
            }
            String kind = entry.substring(split + 1, kindSplit);
            String[] boundNames = entry.substring(kindSplit + 1).split(",");
            Type[] bounds = new Type[boundNames.length];
            for (int i = 0; i < boundNames.length; i++) {
                try {
                    bounds[i] = Class.forName(boundNames[i], false, classLoader);
                } catch (ClassNotFoundException e) {
                    return required;
                }
            }
            arguments[position] = switch (kind) {
                case "var" -> new CdiTypeVariable("T" + position, bounds);
                case "extends" -> new CdiWildcardType(bounds, new Type[0]);
                case "super" -> new CdiWildcardType(new Type[]{Object.class}, bounds);
                case "supervar" -> new CdiWildcardType(new Type[]{Object.class},
                    new Type[]{new CdiTypeVariable("L" + position, bounds)});
                default -> arguments[position];
            };
        }
        return CdiParameterizedType.of((Class<?>) parameterized.getRawType(), arguments);
    }

    /**
     * The type closure of a type: itself and every class and interface it is assignable to, with what the type
     * says about its parameters carried into its supertypes.
     *
     * @param type The type
     * @return The closure, the type first
     */
    public static java.util.List<Type> closureOf(Type type) {
        java.util.List<Type> closure = new java.util.ArrayList<>();
        collectClosure(type, closure);
        return closure;
    }

    private static void collectClosure(@Nullable Type type, java.util.List<Type> closure) {
        if (type == null || type == Object.class) {
            return;
        }
        Class<?> raw = rawClassOf(type);
        if (raw == null) {
            return;
        }
        closure.add(type);
        java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution = new java.util.HashMap<>();
        if (type instanceof ParameterizedType parameterized) {
            java.lang.reflect.TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                substitution.put(variables[i], arguments[i]);
            }
        }
        for (Type anInterface : raw.getGenericInterfaces()) {
            collectClosure(substitute(anInterface, substitution), closure);
        }
        collectClosure(substitute(raw.getGenericSuperclass(), substitution), closure);
    }

    /**
     * The type with the given variables substituted, so that what a subtype says about its parameters carries
     * into the supertypes it collects.
     *
     * @param type         The type
     * @param substitution The variable assignments
     * @return The substituted type
     */
    public static @Nullable Type substitute(@Nullable Type type,
                                            java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution) {
        if (substitution.isEmpty() || type == null) {
            return type;
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            return substitution.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawType) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                substituted[i] = substitute(arguments[i], substitution);
                changed |= substituted[i] != arguments[i];
            }
            return changed ? CdiParameterizedType.of(rawType, substituted) : type;
        }
        return type;
    }

    /**
     * The type an event is fired as, per section 2.8.1: the runtime class of the event object, its type
     * variables resolved by lining the type the event was declared or selected as up against the class's own
     * hierarchy.
     *
     * @param runtimeClass The runtime class of the event object
     * @param declaredType The type the event was declared or selected as
     * @return The event type
     * @throws IllegalArgumentException When a type variable of the class stays unresolved
     */
    public static Type eventTypeOf(Class<?> runtimeClass, Type declaredType) {
        java.lang.reflect.TypeVariable<?>[] variables = runtimeClass.getTypeParameters();
        if (variables.length == 0) {
            return runtimeClass;
        }
        java.util.Map<java.lang.reflect.TypeVariable<?>, Type> resolution = new java.util.HashMap<>();
        Class<?> declaredRaw = rawClassOf(declaredType);
        if (declaredType instanceof ParameterizedType declaredParameterized && declaredRaw != null) {
            // find the class's own view of the declared supertype, whose arguments are expressions in the
            // class's variables, and line the declared arguments up against them
            for (Type supertype : closureOf(CdiParameterizedType.of(runtimeClass))) {
                if (rawClassOf(supertype) == declaredRaw && supertype instanceof ParameterizedType own) {
                    Type[] ownArguments = own.getActualTypeArguments();
                    Type[] declaredArguments = declaredParameterized.getActualTypeArguments();
                    for (int i = 0; i < ownArguments.length && i < declaredArguments.length; i++) {
                        unify(ownArguments[i], declaredArguments[i], resolution);
                    }
                    break;
                }
            }
        }
        Type[] arguments = new Type[variables.length];
        for (int i = 0; i < variables.length; i++) {
            Type resolved = resolution.get(variables[i]);
            if (resolved instanceof java.lang.reflect.WildcardType wildcard) {
                // a wildcard resolves the variable to its bound
                Type[] uppers = wildcard.getUpperBounds();
                resolved = uppers.length > 0 ? uppers[0] : Object.class;
            }
            if (resolved == null || resolved instanceof java.lang.reflect.TypeVariable<?>) {
                throw new IllegalArgumentException("The type variable " + variables[i].getName() + " of "
                    + runtimeClass.getName() + " is not resolved by the type the event was fired as: "
                    + declaredType.getTypeName());
            }
            arguments[i] = resolved;
        }
        return CdiParameterizedType.of(runtimeClass, arguments);
    }

    private static void unify(Type own, Type declared,
                              java.util.Map<java.lang.reflect.TypeVariable<?>, Type> resolution) {
        if (own instanceof java.lang.reflect.TypeVariable<?> variable) {
            resolution.put(variable, declared);
            return;
        }
        if (own instanceof ParameterizedType ownParameterized
            && declared instanceof ParameterizedType declaredParameterized) {
            Type[] ownArguments = ownParameterized.getActualTypeArguments();
            Type[] declaredArguments = declaredParameterized.getActualTypeArguments();
            for (int i = 0; i < ownArguments.length && i < declaredArguments.length; i++) {
                unify(ownArguments[i], declaredArguments[i], resolution);
            }
        }
    }

    public static Type typeOf(Argument<?> argument) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        if (typeParameters.length == 0) {
            return argument.getType();
        }
        Type[] arguments = new Type[typeParameters.length];
        for (int i = 0; i < typeParameters.length; i++) {
            arguments[i] = typeOf(typeParameters[i]);
        }
        return new Parameterized(argument.getType(), arguments);
    }

    /**
     * The argument that describes the given type.
     *
     * @param type The type
     * @param <T>  The type
     * @return The argument
     */
    @SuppressWarnings("unchecked")
    public static <T> Argument<T> argumentOf(Type type) {
        if (type instanceof Class<?> aClass) {
            return (Argument<T>) Argument.of(aClass);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Argument<?>[] resolved = new Argument<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = argumentOf(arguments[i]);
            }
            return (Argument<T>) Argument.of((Class<?>) parameterized.getRawType(), resolved);
        }
        throw new IllegalArgumentException("A bean cannot be looked up by the type " + type + ": only a class and "
            + "a parameterized type describe a bean");
    }

    /**
     * A parameterized type built from an argument, which is what the specification reports a parameterized bean
     * type or observed event type as.
     *
     * @param rawType   The raw type
     * @param arguments The type arguments
     */
    @SuppressWarnings("ArrayRecordComponent")
    private record Parameterized(Class<?> rawType, Type[] arguments) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public @org.jspecify.annotations.Nullable Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other
                && rawType.equals(other.getRawType())
                && java.util.Arrays.equals(arguments, other.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(arguments) ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder(rawType.getName()).append('<');
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(arguments[i].getTypeName());
            }
            return builder.append('>').toString();
        }
    }
}
