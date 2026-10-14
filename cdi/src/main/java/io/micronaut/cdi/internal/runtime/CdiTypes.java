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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.core.type.WildcardArgument;

import io.micronaut.cdi.internal.type.SpecificationTypes;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The types of the specification as the container holds them: each an {@link Argument}, which carries the type
 * arguments, the wildcards and the type variables a type was written with.
 *
 * <p>What a type is - a class, a parameterized type, a wildcard, a type variable, an array of one of them - is
 * asked here, and so is what is above it: the type closure of a class is what the processor recorded of it. A
 * {@link Type} of the specification's API is turned into an argument, and made from one, by
 * {@link SpecificationTypes} and nowhere else.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiTypes {

    private CdiTypes() {
    }

    /**
     * The raw class of a type that was handed in, or {@code null} for one that has none of its own.
     *
     * @param type The type
     * @return The raw class
     */
    public static @Nullable Class<?> rawClassOf(Type type) {
        return SpecificationTypes.rawClassOf(type);
    }

    /**
     * The raw class of a type, or {@code null} for one that has none of its own: a wildcard, a type variable,
     * or an array of a variable.
     *
     * @param type The type
     * @return The raw class
     */
    public static @Nullable Class<?> rawClassOf(Argument<?> type) {
        return type.isWildcard() || isUnresolved(type) ? null : type.getType();
    }

    /**
     * The class that boxes a primitive, or the class itself when it is not one.
     *
     * @param type The class
     * @return The boxed form
     */
    public static Class<?> boxedOf(Class<?> type) {
        return ReflectionUtils.getWrapperType(type);
    }

    /**
     * The name a type is recorded by where only its erasure is: the binary name of its class, followed by a pair
     * of brackets for each dimension of an array - what the processor writes for the parameter types of a method
     * it records, so that the method is found without loading a class by name.
     *
     * @param type The class
     * @return The recorded name
     */
    public static String erasedName(Class<?> type) {
        StringBuilder brackets = new StringBuilder();
        Class<?> component = type;
        while (component.isArray()) {
            brackets.append("[]");
            component = component.getComponentType();
        }
        return component.getName() + brackets;
    }

    /**
     * The executable method of a definition of the given name and erased parameter types, which tell overloads of
     * one name apart: what a producer records of its disposer and an invoker of its method. The types are compared
     * by name, so that none has to be loaded by name, which a native image would need metadata for.
     *
     * @param definition     The definition
     * @param name           The name of the method
     * @param parameterTypes The {@link #erasedName erased names} of its parameter types
     * @return The method, or {@code null} where the definition has no executable method of the signature
     */
    static io.micronaut.inject.@Nullable ExecutableMethod<?, ?> executableMethodOf(
        io.micronaut.inject.BeanDefinition<?> definition, String name, String[] parameterTypes) {
        return definition.findPossibleMethods(name)
            .filter(method -> hasParameterTypes(method.getArgumentTypes(), parameterTypes))
            .findFirst()
            .orElse(null);
    }

    private static boolean hasParameterTypes(Class<?>[] types, String[] parameterTypes) {
        if (types.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!parameterTypes[i].equals(erasedName(types[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The required type of the lookup, as the specification's API reports a type.
     *
     * @param beanType The compiled argument of the lookup
     * @return The required type
     */
    public static Type requiredTypeOf(Argument<?> beanType) {
        return SpecificationTypes.typeOf(beanType);
    }

    /**
     * The type a compiled argument describes, as the specification's API reports a type.
     *
     * @param argument The argument
     * @return The type
     */
    public static Type typeOf(Argument<?> argument) {
        return SpecificationTypes.typeOf(argument);
    }

    /**
     * The argument a bean is looked up by for the given type.
     *
     * @param type The type
     * @param <T>  The type
     * @return The argument
     * @throws IllegalArgumentException For a type that is neither a class nor a parameterized type of classes
     */
    public static <T> Argument<T> argumentOf(Type type) {
        return SpecificationTypes.lookupArgumentOf(type);
    }

    /**
     * Whether the type is an array: of a class, of a parameterized type, or of a type variable.
     *
     * @param type The type
     * @return Whether it is one
     */
    public static boolean isArray(Argument<?> type) {
        // Argument.isArray() is true of a wildcard bounded by an array as well, which is not an array type
        return !type.isWildcard() && type.getType().isArray();
    }

    /**
     * Whether the type is a parameterized type: a class written with type arguments.
     *
     * @param type The type
     * @return Whether it is one
     */
    public static boolean isParameterized(Argument<?> type) {
        return !type.isWildcard() && !isUnresolved(type) && !type.getType().isArray() && type.hasTypeArguments();
    }

    /**
     * Whether the type is a class and nothing more: a class without type arguments, a raw type, a primitive,
     * or an array of one of them.
     *
     * @param type The type
     * @return Whether it is one
     */
    public static boolean isClass(Argument<?> type) {
        return !type.isWildcard() && !isUnresolved(type) && !type.hasTypeArguments();
    }

    /**
     * Whether the type is {@code Object}.
     *
     * @param type The type
     * @return Whether it is
     */
    public static boolean isObject(Argument<?> type) {
        return type.getType() == Object.class && isClass(type);
    }

    /**
     * The class of a class or of a parameterized type, or {@code null} for a type that is neither.
     *
     * @param type The type
     * @return The class
     */
    public static @Nullable Class<?> classOf(Argument<?> type) {
        return isClass(type) || isParameterized(type) ? type.getType() : null;
    }

    /**
     * Adds a type to the given types unless it is among them already.
     *
     * @param types The types
     * @param type  The type to add
     */
    static void addDistinct(List<Argument<?>> types, Argument<?> type) {
        for (Argument<?> each : types) {
            if (each.equalsStructure(type)) {
                return;
            }
        }
        types.add(type);
    }

    /**
     * Whether the type is a type variable or an array of one. {@code Argument.isUnresolvedTypeVariable()} is
     * the variable alone: an array of a variable is an array to it, and here it is neither a class nor a
     * parameterized type.
     */
    private static boolean isUnresolved(Argument<?> type) {
        return type.isUnresolvedTypeVariable() || type.getType().isArray() && !type.isWildcard()
            && type instanceof GenericPlaceholder<?> placeholder && !placeholder.isResolved();
    }

    /**
     * A type variable of the given name and bounds.
     *
     * @param name   The name
     * @param bounds The bounds, none for a variable bounded by {@code Object}
     * @return The variable
     */
    public static Argument<?> variable(String name, Argument<?>... bounds) {
        Argument<?>[] all = bounds.length == 0 ? new Argument<?>[] {Argument.OBJECT_ARGUMENT} : bounds;
        return Argument.ofTypeVariable(all[0].getType(), null, name, null, all[0].getTypeParameters(), all);
    }

    /**
     * The type with each type variable named among the given arguments replaced by the argument, so that what
     * a subtype says about its parameters carries into the supertypes it collects.
     *
     * @param type          The type
     * @param arguments     The variable assignments, by the name of the variable
     * @param intoWildcards Whether the bounds of a wildcard are substituted as well
     * @return The substituted type
     */
    public static Argument<?> substitute(Argument<?> type, Map<String, Argument<?>> arguments,
                                         boolean intoWildcards) {
        if (arguments.isEmpty()) {
            return type;
        }
        if (type.isUnresolvedTypeVariable()) {
            Argument<?> argument = arguments.get(((GenericPlaceholder<?>) type).getVariableName());
            return argument != null ? argument : type;
        }
        if (isParameterized(type)) {
            Argument<?>[] substituted = substitute(List.of(type.getTypeParameters()), arguments, intoWildcards);
            // written the way the type was: a variable resolved to a parameterized type stays a resolved
            // variable, and the annotations of the use are kept
            return substituted == null ? type : type.withTypeParameters(substituted);
        }
        if (intoWildcards && type instanceof WildcardArgument<?> wildcard) {
            Argument<?>[] upper = substitute(wildcard.getUpperBounds(), arguments, true);
            Argument<?>[] lower = substitute(wildcard.getLowerBounds(), arguments, true);
            if (upper == null && lower == null) {
                return type;
            }
            Argument<?>[] uppers = upper != null ? upper : wildcard.getUpperBounds().toArray(Argument.ZERO_ARGUMENTS);
            Argument<?>[] lowers = lower != null ? lower : wildcard.getLowerBounds().toArray(Argument.ZERO_ARGUMENTS);
            return Argument.ofWildcard(uppers[0].getType(), null, null, uppers[0].getTypeParameters(), uppers, lowers);
        }
        return type;
    }

    /**
     * The given types substituted, or {@code null} where the substitution changes none of them.
     */
    private static Argument<?> @Nullable [] substitute(List<Argument<?>> types, Map<String, Argument<?>> arguments,
                                                       boolean intoWildcards) {
        Argument<?>[] substituted = new Argument<?>[types.size()];
        boolean changed = false;
        for (int i = 0; i < substituted.length; i++) {
            substituted[i] = substitute(types.get(i), arguments, intoWildcards);
            changed |= substituted[i] != types.get(i);
        }
        return changed ? substituted : null;
    }

    /**
     * The type closure of a type: the type, and every class and interface above it with the type arguments the
     * hierarchy gives each, {@code Object} left out.
     *
     * <p>It is read from what the processor recorded of the class. For a class the application was not compiled
     * with, the module that reads classes answers where it is there; otherwise the closure is the type alone.</p>
     *
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param type The type
     * @return The closure, the type first
     */
    public static List<Argument<?>> closureOf(@Nullable RecordedTypeIndex index, Argument<?> type) {
        return closureOf(index, type, false);
    }

    /**
     * The closure a bean's types are taken from: the {@link #closureOf type closure}, except that an array
     * and a primitive have no closure beyond themselves (section 2.2.1).
     *
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param type The type
     * @return The closure, the type first
     */
    public static List<Argument<?>> beanTypeClosureOf(@Nullable RecordedTypeIndex index, Argument<?> type) {
        return closureOf(index, type, true);
    }

    /**
     * The closure the types of a bean of the given class are taken from, starting at the class over its own
     * type variables.
     *
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param beanClass The bean class
     * @return The closure, the class first
     */
    static List<Argument<?>> beanTypeClosureOf(@Nullable RecordedTypeIndex index, Class<?> beanClass) {
        RecordedTypeIndex.Entry recorded = recordOf(index, beanClass);
        if (recorded != null) {
            return closureOf(index, recorded.declaredTypeOf(beanClass), true);
        }
        CdiReflection reflection = reflection(index);
        return closureOf(index, reflection != null
            ? SpecificationTypes.argumentOf(reflection.declaredTypeOf(beanClass)) : Argument.of(beanClass), true);
    }

    private static List<Argument<?>> closureOf(@Nullable RecordedTypeIndex index, Argument<?> type,
                                              boolean arrayStops) {
        Class<?> raw = rawClassOf(type);
        if (raw == null || isObject(type)) {
            return new ArrayList<>();
        }
        if (arrayStops && (raw.isArray() || raw.isPrimitive())) {
            return new ArrayList<>(List.of(type));
        }
        RecordedTypeIndex.Entry recorded = recordOf(index, raw);
        if (recorded != null) {
            List<Argument<?>> closure = recorded.closureOf(type);
            if (closure != null) {
                return closure;
            }
        }
        CdiReflection reflection = reflection(index);
        if (reflection != null) {
            // the module that reads classes speaks the types of the reflection API
            return SpecificationTypes.argumentsOf(
                reflection.typeClosureOf(SpecificationTypes.typeOf(type), arrayStops));
        }
        // nothing is known of what is above the type without reading it: the type itself is all there is,
        // and what is above a class is asked of the class by whoever compares against it
        return new ArrayList<>(List.of(type));
    }

    /**
     * Whether the types above the given class can be said: the processor recorded them, or the module that
     * reads classes is there to read them.
     *
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param type The class
     * @return Whether its closure is known
     */
    static boolean knowsClosureOf(@Nullable RecordedTypeIndex index, Class<?> type) {
        return recordOf(index, type) != null || reflection(index) != null;
    }

    private static RecordedTypeIndex.@Nullable Entry recordOf(@Nullable RecordedTypeIndex index, Class<?> type) {
        return index == null ? null : index.of(type.getName());
    }

    private static @Nullable CdiReflection reflection(@Nullable RecordedTypeIndex index) {
        return index == null ? null : index.reflection();
    }

    /**
     * Whether an object of the given class is an event whose type its class alone does not say: the class
     * declares type variables. Known from the record of a class the application was compiled with, and from
     * the class itself where the module that reads classes is there.
     *
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param type The class
     * @return Whether it declares type variables, as far as can be known
     */
    static boolean declaresTypeVariables(@Nullable RecordedTypeIndex index, Class<?> type) {
        RecordedTypeIndex.Entry recorded = recordOf(index, type);
        if (recorded != null) {
            return !recorded.variables().isEmpty();
        }
        CdiReflection reflection = reflection(index);
        return reflection != null && reflection.declaredTypeOf(type) != type;
    }

    /**
     * The type of an event of the given runtime class that was fired as the given type, where both are the
     * types of the specification's API.
     *
     * @param index        The generic hierarchies of the container asking, {@code null} where it has none yet
     * @param runtimeClass The class of the event object
     * @param declaredType The type the event was fired as
     * @return The event type
     * @throws IllegalArgumentException Where the type the event was fired as leaves a variable of the class
     *                                  unresolved
     * @see #eventTypeOf(RecordedTypeIndex, Class, Argument)
     */
    public static Type eventTypeOf(@Nullable RecordedTypeIndex index, Class<?> runtimeClass, Type declaredType) {
        return SpecificationTypes.typeOf(
            eventTypeOf(index, runtimeClass, SpecificationTypes.argumentOf(declaredType)));
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
     * @param index The generic hierarchies of the container asking, which also reach its module that reads
     *              classes; {@code null} where it has none yet, and nothing above a type is known
     * @param runtimeClass The class of the event object
     * @param declaredType The type the event was fired as
     * @return The event type
     * @throws IllegalArgumentException Where the type the event was fired as leaves a variable of the class
     *                                  unresolved
     */
    public static Argument<?> eventTypeOf(@Nullable RecordedTypeIndex index, Class<?> runtimeClass,
                                          Argument<?> declaredType) {
        RecordedTypeIndex.Entry recorded = recordOf(index, runtimeClass);
        if (recorded == null) {
            CdiReflection reflection = reflection(index);
            if (reflection != null) {
                return SpecificationTypes.argumentOf(
                    reflection.eventTypeOf(runtimeClass, SpecificationTypes.typeOf(declaredType)));
            }
            return rawClassOf(declaredType) == runtimeClass ? declaredType : Argument.of(runtimeClass);
        }
        List<String> variables = recorded.variables();
        if (variables.isEmpty()) {
            return Argument.of(runtimeClass);
        }
        Map<String, Argument<?>> resolution = new HashMap<>();
        Class<?> declaredRaw = rawClassOf(declaredType);
        if (isParameterized(declaredType) && declaredRaw != null) {
            List<Argument<?>> closure = recorded.closureOf(recorded.declaredTypeOf(runtimeClass));
            if (closure != null) {
                for (Argument<?> supertype : closure) {
                    if (rawClassOf(supertype) == declaredRaw && isParameterized(supertype)) {
                        unify(supertype, declaredType, resolution);
                        break;
                    }
                }
            }
        }
        Argument<?>[] arguments = new Argument<?>[variables.size()];
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> resolved = resolution.get(variables.get(i));
            if (resolved instanceof WildcardArgument<?> wildcard) {
                resolved = wildcard.getUpperBounds().get(0);
            }
            if (resolved == null || resolved.isUnresolvedTypeVariable()) {
                throw new IllegalArgumentException("The type variable " + variables.get(i) + " of "
                    + runtimeClass.getName() + " is not resolved by the type the event was fired as: "
                    + SpecificationTypes.typeOf(declaredType).getTypeName()
                    + ". MicronautEvent.select(Argument) states the type of an event in full");
            }
            arguments[i] = resolved;
        }
        return Argument.of(runtimeClass, (String) null, arguments);
    }

    private static void unify(Argument<?> own, Argument<?> declared, Map<String, Argument<?>> resolution) {
        if (own.isUnresolvedTypeVariable()) {
            resolution.put(((GenericPlaceholder<?>) own).getVariableName(), declared);
            return;
        }
        if (isParameterized(own) && isParameterized(declared)) {
            Argument<?>[] ownArguments = own.getTypeParameters();
            Argument<?>[] declaredArguments = declared.getTypeParameters();
            for (int i = 0; i < ownArguments.length && i < declaredArguments.length; i++) {
                unify(ownArguments[i], declaredArguments[i], resolution);
            }
        }
    }
}
