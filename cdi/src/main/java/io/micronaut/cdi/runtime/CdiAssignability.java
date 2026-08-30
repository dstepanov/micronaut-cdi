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
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Set;

/**
 * The rules of section 2.4.2 by which a bean is or is not eligible for an injection point, applied to types and
 * qualifiers on their own.
 *
 * <p>The specification asks a container to answer that question about types and qualifiers it is handed rather
 * than about beans it knows, which is what {@code BeanContainer.isMatchingBean} and
 * {@code BeanContainer.isMatchingEvent} are. The answer cannot be delegated to Micronaut, because there is no
 * bean to resolve — so the rules are applied here, on the types and the annotations themselves.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAssignability {

    private CdiAssignability() {
    }

    /**
     * Whether a bean with the given types and qualifiers is eligible for an injection point of the given required
     * type and qualifiers.
     *
     * @param beanTypes          The bean types of the bean
     * @param beanQualifiers     The qualifiers of the bean
     * @param requiredType       The required type of the injection point
     * @param requiredQualifiers The required qualifiers of the injection point
     * @return Whether the bean is eligible
     */
    public static boolean isMatchingBean(Set<Type> beanTypes,
                                         Set<Annotation> beanQualifiers,
                                         Type requiredType,
                                         Set<Annotation> requiredQualifiers) {
        requireNonNull(beanTypes, "The bean types");
        requireNonNull(beanQualifiers, "The bean qualifiers");
        requireNonNull(requiredType, "The required type");
        requireNonNull(requiredQualifiers, "The required qualifiers");
        requireQualifiers(beanQualifiers);
        requireQualifiers(requiredQualifiers);
        requireNoTypeVariable(requiredType);
        boolean assignable = false;
        for (Type beanType : beanTypes) {
            // a type that is not a legal bean type — one with a wildcard in it — is passed over rather than
            // failing the whole set, and matches nothing, not even itself
            if (isLegalBeanType(beanType) && isAssignable(requiredType, beanType)) {
                assignable = true;
                break;
            }
        }
        if (!assignable) {
            return false;
        }
        // the rule of section 2.1.3, applied to the given sets: every bean has Any, and one that names nothing
        // beyond Any and a name has the default qualifier; an injection point that names no qualifier is
        // looking for the default one
        Set<Annotation> effective = new java.util.HashSet<>(beanQualifiers);
        effective.add(Any.Literal.INSTANCE);
        boolean namesOne = false;
        for (Annotation qualifier : beanQualifiers) {
            if (!(qualifier instanceof Any) && !(qualifier instanceof jakarta.inject.Named)) {
                namesOne = true;
                break;
            }
        }
        if (!namesOne) {
            effective.add(Default.Literal.INSTANCE);
        }
        Set<Annotation> required = requiredQualifiers.isEmpty() ? Set.of(Default.Literal.INSTANCE) : requiredQualifiers;
        for (Annotation qualifier : required) {
            if (qualifier instanceof Any) {
                continue;
            }
            boolean satisfied = false;
            for (Annotation candidate : effective) {
                if (CdiAnnotations.areEquivalent(qualifier, candidate)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether any of the bean types matches the required type, by the type rules of section 2.4.2.1 alone.
     *
     * @param beanTypes    The bean types
     * @param requiredType The required type
     * @return Whether the types match
     */
    public static boolean isTypeMatching(Set<Type> beanTypes, Type requiredType) {
        for (Type beanType : beanTypes) {
            if (isLegalBeanType(beanType) && isAssignable(requiredType, beanType)) {
                return true;
            }
        }
        return false;
    }

    private static void requireNonNull(@Nullable Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
    }

    private static void requireQualifiers(Set<Annotation> qualifiers) {
        for (Annotation qualifier : qualifiers) {
            if (!ExtensionQualifiers.isQualifier(qualifier.annotationType())) {
                throw new IllegalArgumentException(qualifier.annotationType().getName() + " is not a qualifier");
            }
        }
    }

    /**
     * Whether an event of the given type and qualifiers notifies an observer method that observes the given type
     * with the given qualifiers.
     *
     * <p>The rule is the one of section 2.8.3, which is the reverse of the one for an injection point in its
     * qualifiers: the observer is notified when the qualifiers it observes are among the ones the event was fired
     * with, rather than the other way round.</p>
     *
     * @param specifiedType           The type of the event
     * @param specifiedQualifiers     The qualifiers of the event
     * @param observedEventType       The type the observer observes
     * @param observedEventQualifiers The qualifiers the observer observes
     * @return Whether the observer is notified
     */
    public static boolean isMatchingEvent(Type specifiedType,
                                          Set<Annotation> specifiedQualifiers,
                                          Type observedEventType,
                                          Set<Annotation> observedEventQualifiers) {
        requireNonNull(specifiedType, "The event type");
        requireNonNull(specifiedQualifiers, "The event qualifiers");
        requireNonNull(observedEventType, "The observed type");
        requireNonNull(observedEventQualifiers, "The observed qualifiers");
        requireQualifiers(specifiedQualifiers);
        requireQualifiers(observedEventQualifiers);
        requireNoTypeVariable(specifiedType);
        if (specifiedType instanceof ParameterizedType parameterized) {
            // an event type is stricter than a required bean type: a type variable anywhere in it leaves the
            // event without a type to be observed as
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof TypeVariable<?>) {
                    throw new IllegalArgumentException(
                        "A type variable does not describe an event: " + specifiedType);
                }
            }
        }
        boolean assignable = false;
        for (Type eventType : typeClosureOf(specifiedType)) {
            if (isEventAssignable(observedEventType, eventType)) {
                assignable = true;
                break;
            }
        }
        if (!assignable) {
            return false;
        }
        // the qualifiers an event was fired with always include Any
        for (Annotation observed : observedEventQualifiers) {
            if (observed.annotationType() == Any.class) {
                continue;
            }
            if (specifiedQualifiers.isEmpty() && observed.annotationType() == Default.class) {
                continue;
            }
            // compared as the specification compares qualifiers, because either side may be an annotation the
            // container synthesized from compiled metadata rather than a literal with the reflective contract
            boolean present = false;
            for (Annotation specified : specifiedQualifiers) {
                if (CdiAnnotations.areEquivalent(specified, observed)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the type is one a bean may have: a wildcard anywhere in it makes it not one.
     */
    /**
     * Whether the type may be a bean type: section 2.2.1 excludes a parameterized type that contains a
     * wildcard, at any depth, from the types a bean can be resolved by.
     *
     * @param type The type
     * @return Whether it is a legal bean type
     */
    static boolean isLegalBeanType(Type type) {
        if (type instanceof WildcardType) {
            return false;
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (!isLegalBeanType(argument)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The event type and every supertype of it, with the parameters each level was written with: an observer of
     * {@code Baz<String>} hears an event whose class extends {@code Baz<String>}, and only the closure knows
     * that it does.
     */
    private static java.util.List<Type> typeClosureOf(Type type) {
        java.util.List<Type> closure = new java.util.ArrayList<>(CdiTypes.closureOf(type));
        // every type's closure ends in Object, which collecting skips so that the many chains that all end
        // there contribute it only once
        closure.add(Object.class);
        return closure;
    }

    /**
     * Whether an event of the given type notifies an observer of the observed type, the way section 2.8.3 has
     * it: the observed type is a supertype of the event type — real subtyping, unlike the matching of bean
     * types, because an observer of the supertype hears the events of every subtype.
     */
    private static boolean isEventAssignable(Type observed, Type event) {
        if (observed.equals(event)) {
            return true;
        }
        if (observed instanceof TypeVariable<?> variable) {
            // an observed type variable observes whatever fits its bounds
            Class<?> eventRaw = upperRawOf(event);
            if (eventRaw == null) {
                return false;
            }
            for (Type bound : variable.getBounds()) {
                Class<?> boundRaw = upperRawOf(bound);
                if (boundRaw != null && !boundRaw.isAssignableFrom(eventRaw)) {
                    return false;
                }
            }
            return true;
        }
        if (observed instanceof java.lang.reflect.GenericArrayType observedArray) {
            Type eventComponent = event instanceof java.lang.reflect.GenericArrayType eventArray
                ? eventArray.getGenericComponentType()
                : event instanceof Class<?> eventClass && eventClass.isArray()
                    ? eventClass.getComponentType() : null;
            return eventComponent != null
                && isEventAssignable(observedArray.getGenericComponentType(), eventComponent);
        }
        Class<?> observedRaw = rawTypeOf(observed);
        Class<?> eventRaw = rawTypeOf(event);
        if (observedRaw == null || eventRaw == null || !observedRaw.isAssignableFrom(eventRaw)) {
            return false;
        }
        if (observed instanceof ParameterizedType observedParameterized) {
            if (!(event instanceof ParameterizedType eventParameterized)) {
                return saysNothing(observedParameterized.getActualTypeArguments());
            }
            Type[] observedArguments = observedParameterized.getActualTypeArguments();
            Type[] eventArguments = eventParameterized.getActualTypeArguments();
            if (observedArguments.length != eventArguments.length) {
                // not the comparable pair: the event's closure carries the properly-parameterized supertype
                // as its own entry, and that one is what the observed type is judged against
                return false;
            }
            for (int i = 0; i < observedArguments.length; i++) {
                if (!eventArgumentMatches(observedArguments[i], eventArguments[i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the bean type matches the required type, the way section 2.4.2.1 has types match: by being the
     * same type, rather than by subtyping — a bean is resolvable by its supertype because the supertype is among
     * its bean types, not because assignability climbs the hierarchy for it.
     *
     * <p>{@code Object} is matched by everything, since every bean has it among its types whether or not the
     * caller listed it; and a primitive and the class that boxes it are the same type.</p>
     */
    private static boolean isAssignable(Type required, Type candidate) {
        if (required.equals(candidate)) {
            return true;
        }
        Class<?> requiredRaw = rawTypeOf(required);
        Class<?> candidateRaw = rawTypeOf(candidate);
        if (requiredRaw == null || candidateRaw == null) {
            // a type variable or a wildcard names no type of its own, and is passed over
            return false;
        }
        if (requiredRaw == Object.class && !(required instanceof ParameterizedType)) {
            return true;
        }
        if (!boxed(requiredRaw).equals(boxed(candidateRaw))) {
            return false;
        }
        boolean requiredParameterized = required instanceof ParameterizedType;
        boolean candidateParameterized = candidate instanceof ParameterizedType;
        if (!requiredParameterized && !candidateParameterized) {
            return true;
        }
        if (!requiredParameterized) {
            // a parameterized bean type matches the raw required type when its own parameters say nothing:
            // unbounded variables, or Object
            return saysNothing(((ParameterizedType) candidate).getActualTypeArguments());
        }
        Type[] requiredArguments = ((ParameterizedType) required).getActualTypeArguments();
        if (!candidateParameterized) {
            // and a raw bean type matches a parameterized required type on the same terms
            return saysNothing(requiredArguments);
        }
        Type[] candidateArguments = ((ParameterizedType) candidate).getActualTypeArguments();
        if (requiredArguments.length != candidateArguments.length) {
            return false;
        }
        for (int i = 0; i < requiredArguments.length; i++) {
            if (!argumentMatches(requiredArguments[i], candidateArguments[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one pair of event type arguments matches, per the cases of section 2.8.3: the observed side may
     * name a wildcard or a variable, and either admits what fits its bounds.
     */
    private static boolean eventArgumentMatches(Type observed, Type event) {
        if (observed instanceof WildcardType wildcard) {
            return withinBounds(event, wildcard);
        }
        if (observed instanceof TypeVariable<?> variable) {
            Class<?> eventRaw = upperRawOf(event);
            if (eventRaw == null) {
                return false;
            }
            for (Type bound : variable.getBounds()) {
                Class<?> boundRaw = upperRawOf(bound);
                if (boundRaw != null && !boundRaw.isAssignableFrom(eventRaw)) {
                    return false;
                }
            }
            return true;
        }
        if (observed.equals(event)) {
            return true;
        }
        Class<?> observedRaw = rawTypeOf(observed);
        Class<?> eventRaw = rawTypeOf(event);
        if (observedRaw == null || !observedRaw.equals(eventRaw)) {
            return false;
        }
        if (observed instanceof ParameterizedType observedParameterized
            && event instanceof ParameterizedType eventParameterized) {
            Type[] observedArguments = observedParameterized.getActualTypeArguments();
            Type[] eventArguments = eventParameterized.getActualTypeArguments();
            if (observedArguments.length != eventArguments.length) {
                return false;
            }
            for (int i = 0; i < observedArguments.length; i++) {
                if (!eventArgumentMatches(observedArguments[i], eventArguments[i])) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Whether one pair of type arguments matches, per the cases of section 2.4.2.1.
     */
    private static boolean argumentMatches(Type required, Type candidate) {
        if (required instanceof WildcardType wildcard) {
            return withinBounds(candidate, wildcard);
        }
        if (required instanceof TypeVariable<?> requiredVariable) {
            if (candidate instanceof TypeVariable<?> beanVariable) {
                // both are variables: the required one's bounds must fit inside the bean one's
                return boundsInside(requiredVariable.getBounds(), beanVariable.getBounds());
            }
            // a required type variable matches no actual bean argument
            return false;
        }
        if (candidate instanceof TypeVariable<?> variable) {
            // an actual required argument matches a variable whose upper bounds it fits
            for (Type bound : variable.getBounds()) {
                Class<?> boundRaw = rawTypeOf(bound);
                Class<?> requiredRaw = rawTypeOf(required);
                if (boundRaw == null || requiredRaw == null || !boundRaw.isAssignableFrom(requiredRaw)) {
                    return false;
                }
            }
            return true;
        }
        if (required instanceof ParameterizedType requiredParameterized
            && candidate instanceof ParameterizedType candidateParameterized) {
            // two actual parameterized arguments: identical raw types with pairwise-matching arguments, the
            // first case of section 2.4.2.1 applied recursively
            if (!requiredParameterized.getRawType().equals(candidateParameterized.getRawType())) {
                return false;
            }
            Type[] requiredArguments = requiredParameterized.getActualTypeArguments();
            Type[] candidateArguments = candidateParameterized.getActualTypeArguments();
            if (requiredArguments.length != candidateArguments.length) {
                return false;
            }
            for (int i = 0; i < requiredArguments.length; i++) {
                if (!argumentMatches(requiredArguments[i], candidateArguments[i])) {
                    return false;
                }
            }
            return true;
        }
        return required.equals(candidate);
    }

    /**
     * Whether every bound of the outer set is satisfied by the inner set: for each outer bound, some inner
     * bound is a subtype of it, so that anything within the inner bounds is within the outer.
     */
    private static boolean boundsInside(Type[] inner, Type[] outer) {
        for (Type outerBound : outer) {
            Class<?> outerRaw = upperRawOf(outerBound);
            if (outerRaw == null || outerRaw == Object.class) {
                continue;
            }
            boolean covered = false;
            for (Type innerBound : inner) {
                Class<?> innerRaw = upperRawOf(innerBound);
                if (innerRaw != null && outerRaw.isAssignableFrom(innerRaw)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    /**
     * The raw type a bound reaches: a bound that is itself a type variable is bounded by its own first bound,
     * transitively.
     */
    private static @Nullable Class<?> upperRawOf(Type bound) {
        if (bound instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : upperRawOf(bounds[0]);
        }
        return rawTypeOf(bound);
    }

    /**
     * Whether the candidate argument fits within the wildcard's bounds.
     */
    private static boolean withinBounds(Type candidate, WildcardType wildcard) {
        if (candidate instanceof TypeVariable<?> variable) {
            // section 2.4.2.1 relaxes the wildcard for a variable: its upper bound may be assignable to or
            // from the wildcard's upper bound, and must be assignable from the wildcard's lower bound
            Type[] beanBounds = variable.getBounds();
            Class<?> variableUpper = beanBounds.length == 0 ? Object.class : upperRawOf(beanBounds[0]);
            if (variableUpper == null) {
                return false;
            }
            for (Type upper : wildcard.getUpperBounds()) {
                Class<?> upperRaw = upperRawOf(upper);
                if (upperRaw != null && !upperRaw.isAssignableFrom(variableUpper)
                    && !variableUpper.isAssignableFrom(upperRaw)) {
                    return false;
                }
            }
            for (Type lower : wildcard.getLowerBounds()) {
                if (lower instanceof TypeVariable<?> lowerVariable) {
                    // whatever satisfies the lower variable must satisfy the bean variable's bounds
                    if (!boundsInside(lowerVariable.getBounds(), beanBounds)) {
                        return false;
                    }
                    continue;
                }
                Class<?> lowerRaw = rawTypeOf(lower);
                if (lowerRaw == null) {
                    continue;
                }
                for (Type beanBound : beanBounds) {
                    Class<?> beanRaw = upperRawOf(beanBound);
                    if (beanRaw != null && !beanRaw.isAssignableFrom(lowerRaw)) {
                        return false;
                    }
                }
            }
            return true;
        }
        Class<?> candidateRaw = rawTypeOf(candidate);
        if (candidateRaw == null) {
            return false;
        }
        for (Type upper : wildcard.getUpperBounds()) {
            Class<?> upperRaw = rawTypeOf(upper);
            if (upperRaw != null && !upperRaw.isAssignableFrom(candidateRaw)) {
                return false;
            }
        }
        for (Type lower : wildcard.getLowerBounds()) {
            Class<?> lowerRaw = lower instanceof TypeVariable<?> lowerVariable
                ? upperRawOf(lowerVariable.getBounds().length == 0 ? Object.class : lowerVariable.getBounds()[0])
                : rawTypeOf(lower);
            if (lowerRaw != null && !candidateRaw.isAssignableFrom(lowerRaw)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the arguments of a parameterized type say nothing at all: every one an unbounded variable, an
     * unbounded wildcard, or {@code Object}.
     */
    private static boolean saysNothing(Type[] arguments) {
        for (Type argument : arguments) {
            if (argument == Object.class) {
                continue;
            }
            if (argument instanceof TypeVariable<?> variable) {
                Type[] bounds = variable.getBounds();
                if (bounds.length == 0 || (bounds.length == 1 && bounds[0] == Object.class)) {
                    continue;
                }
                return false;
            }
            if (argument instanceof WildcardType wildcard) {
                if (wildcard.getLowerBounds().length == 0
                    && (wildcard.getUpperBounds().length == 0
                    || (wildcard.getUpperBounds().length == 1 && wildcard.getUpperBounds()[0] == Object.class))) {
                    continue;
                }
                return false;
            }
            return false;
        }
        return true;
    }

    /**
     * The class that boxes a primitive, since a primitive and its box are one type here.
     */
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static @Nullable Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> aClass) {
            return aClass;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    private static void requireNoTypeVariable(Type type) {
        // a parameterized type may carry type variables among its arguments — section 2.4.2.1 has rules for
        // matching them — but a bare type variable names nothing to resolve
        if (type instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("A type variable does not describe a bean or an event: " + type);
        }
    }
}
