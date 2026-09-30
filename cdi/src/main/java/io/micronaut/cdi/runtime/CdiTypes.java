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
        if (type instanceof java.lang.reflect.GenericArrayType array) {
            // the raw class of an array of a parameterized type is the array of the raw component
            Class<?> component = rawClassOf(array.getGenericComponentType());
            return component == null ? null : component.arrayType();
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
     * The type closure of a type: the type, and every class and interface above it with the type arguments the
     * hierarchy gives each, {@code Object} left out.
     *
     * <p>It is read from what the processor recorded of the class. For a class the application was not compiled
     * with, the module that reads classes answers where it is there; otherwise the closure is the type alone.</p>
     *
     * @param type The type
     * @return The closure, the type first
     */
    public static java.util.List<Type> closureOf(Type type) {
        return closureOf(type, false);
    }

    /**
     * The closure a bean's types are taken from: the {@link #closureOf type closure}, except that an array
     * and a primitive have no closure beyond themselves (section 2.2.1).
     *
     * @param type The type
     * @return The closure, the type first
     */
    public static java.util.List<Type> beanTypeClosureOf(Type type) {
        return closureOf(type, true);
    }

    /**
     * The closure the types of a bean of the given class are taken from, starting at the class over its own
     * type variables.
     *
     * @param beanClass The bean class
     * @return The closure, the class first
     */
    static java.util.List<Type> beanTypeClosureOf(Class<?> beanClass) {
        RecordedTypeIndex.Entry recorded = recordOf(beanClass);
        if (recorded != null) {
            return closureOf(recorded.declaredTypeOf(beanClass), true);
        }
        CdiReflection reflection = reflection();
        return closureOf(reflection != null ? reflection.declaredTypeOf(beanClass) : beanClass, true);
    }

    private static java.util.List<Type> closureOf(Type type, boolean arrayStops) {
        Class<?> raw = rawClassOf(type);
        if (raw == null || type == Object.class) {
            return new java.util.ArrayList<>();
        }
        if (arrayStops && (raw.isArray() || raw.isPrimitive())) {
            return new java.util.ArrayList<>(java.util.List.of(type));
        }
        RecordedTypeIndex.Entry recorded = recordOf(raw);
        if (recorded != null) {
            java.util.List<Type> closure = recorded.closureOf(type);
            if (closure != null) {
                return closure;
            }
        }
        CdiReflection reflection = reflection();
        if (reflection != null) {
            return reflection.typeClosureOf(type, arrayStops);
        }
        // nothing is known of what is above the type without reading it: the type itself is all there is,
        // and what is above a class is asked of the class by whoever compares against it
        return new java.util.ArrayList<>(java.util.List.of(type));
    }

    /**
     * Whether the types above the given class can be said: the processor recorded them, or the module that
     * reads classes is there to read them.
     *
     * @param type The class
     * @return Whether its closure is known
     */
    static boolean knowsClosureOf(Class<?> type) {
        return recordOf(type) != null || reflection() != null;
    }

    private static RecordedTypeIndex.@Nullable Entry recordOf(Class<?> type) {
        RecordedTypeIndex index = RecordedTypeIndex.current();
        return index == null ? null : index.of(type.getName());
    }

    private static @Nullable CdiReflection reflection() {
        io.micronaut.context.BeanContext context = CdiRunning.currentContext();
        return context == null ? null : context.findBean(CdiReflection.class).orElse(null);
    }

    /**
     * Whether an object of the given class is an event whose type its class alone does not say: the class
     * declares type variables. Known from the record of a class the application was compiled with, and from
     * the class itself where the module that reads classes is there.
     *
     * @param type The class
     * @return Whether it declares type variables, as far as can be known
     */
    static boolean declaresTypeVariables(Class<?> type) {
        RecordedTypeIndex.Entry recorded = recordOf(type);
        if (recorded != null) {
            return !recorded.variables().isEmpty();
        }
        CdiReflection reflection = reflection();
        return reflection != null && reflection.declaredTypeOf(type) != type;
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
     * The type of an event of the given runtime class that was fired as the given type (section 2.8.1): the
     * class, with the type variables it declares resolved from the type the event was fired as.
     *
     * <p>What the class declares is read from the record the processor made of it, and a variable the type it
     * was fired as leaves unresolved is refused there. A class the application was not compiled with is asked
     * of the module that reads classes where it is there; otherwise the event is of the type it was fired as
     * where that names its class, and of its raw class - which the types above it are matched by as raw
     * classes - where it names a supertype. An event of a generic class is fired as its full type, with
     * nothing read, through {@code MicronautEvent.select(Argument)}.</p>
     *
     * @param runtimeClass The class of the event object
     * @param declaredType The type the event was fired as
     * @return The event type
     * @throws IllegalArgumentException Where the type the event was fired as leaves a variable of the class
     *                                  unresolved
     */
    public static Type eventTypeOf(Class<?> runtimeClass, Type declaredType) {
        RecordedTypeIndex.Entry recorded = recordOf(runtimeClass);
        if (recorded == null) {
            CdiReflection reflection = reflection();
            if (reflection != null) {
                return reflection.eventTypeOf(runtimeClass, declaredType);
            }
            return rawClassOf(declaredType) == runtimeClass ? declaredType : runtimeClass;
        }
        java.util.List<String> variables = recorded.variables();
        if (variables.isEmpty()) {
            return runtimeClass;
        }
        java.util.Map<String, Type> resolution = new java.util.HashMap<>();
        Class<?> declaredRaw = rawClassOf(declaredType);
        if (declaredType instanceof ParameterizedType declaredParameterized && declaredRaw != null) {
            java.util.List<Type> closure = recorded.closureOf(recorded.declaredTypeOf(runtimeClass));
            if (closure != null) {
                for (Type supertype : closure) {
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
        }
        Type[] arguments = new Type[variables.size()];
        for (int i = 0; i < arguments.length; i++) {
            Type resolved = resolution.get(variables.get(i));
            if (resolved instanceof java.lang.reflect.WildcardType wildcard) {
                Type[] uppers = wildcard.getUpperBounds();
                resolved = uppers.length > 0 ? uppers[0] : Object.class;
            }
            if (resolved == null || resolved instanceof java.lang.reflect.TypeVariable<?>) {
                throw new IllegalArgumentException("The type variable " + variables.get(i) + " of "
                    + runtimeClass.getName() + " is not resolved by the type the event was fired as: "
                    + declaredType.getTypeName() + ". MicronautEvent.select(Argument) states the type of an event "
                    + "in full");
            }
            arguments[i] = resolved;
        }
        return CdiParameterizedType.of(runtimeClass, arguments);
    }

    private static void unify(Type own, Type declared, java.util.Map<String, Type> resolution) {
        if (own instanceof java.lang.reflect.TypeVariable<?> variable) {
            resolution.put(variable.getName(), declared);
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

    /**
     * The type an argument describes, as the {@code java.lang.reflect.Type} the specification's interfaces are
     * written in: the class, or the class parameterized by what its type parameters describe.
     *
     * @param argument The argument
     * @return The type
     */
    public static Type typeOf(Argument<?> argument) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        if (typeParameters.length == 0) {
            return argument.getType();
        }
        Type[] arguments = new Type[typeParameters.length];
        for (int i = 0; i < typeParameters.length; i++) {
            arguments[i] = typeOf(typeParameters[i]);
        }
        return CdiParameterizedType.of(argument.getType(), arguments);
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
}
