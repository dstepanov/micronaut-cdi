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
        if (!isEventTypeMatching(observedEventType, specifiedType)) {
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
     * Whether an event of the given type notifies an observer of the observed type, by the type rules of section
     * 2.8.3 alone: the qualifiers of either side, and what the container's own API refuses of an event type, are
     * not looked at.
     *
     * @param observedEventType The type the observer observes
     * @param eventType         The type of the event
     * @return Whether the types match
     */
    public static boolean isEventTypeMatching(Type observedEventType, Type eventType) {
        for (Type type : typeClosureOf(eventType)) {
            if (isEventAssignable(observedEventType, type)) {
                return true;
            }
        }
        return false;
    }

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
            return assignableToAll(uppermostBoundsOf(variable), event);
        }
        if (isArray(observed) || isArray(event)) {
            // arrays are observed by their components, as the language assigns them: covariantly for classes,
            // with no boxing, and by these rules again for a parameterized component
            Type observedComponent = componentOf(observed);
            Type eventComponent = componentOf(event);
            if (observedComponent == null || eventComponent == null) {
                return observed == Object.class;
            }
            if (observedComponent instanceof Class<?> observedClass && eventComponent instanceof Class<?> eventClass) {
                return observedClass.isAssignableFrom(eventClass);
            }
            return isEventAssignable(observedComponent, eventComponent);
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
        if (isArray(required) || isArray(candidate)) {
            // two array types match when their element types do, by these rules again; a class component is the
            // same type or not, with no boxing, since an int[] is not an Integer[]
            Type requiredComponent = componentOf(required);
            Type candidateComponent = componentOf(candidate);
            if (requiredComponent == null || candidateComponent == null) {
                return required == Object.class;
            }
            if (requiredComponent instanceof Class<?> && candidateComponent instanceof Class<?>) {
                return requiredComponent.equals(candidateComponent);
            }
            return isAssignable(requiredComponent, candidateComponent);
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
            // the event type parameter is assignable to the upper bound of the observed variable
            return assignableToAll(uppermostBoundsOf(variable), event);
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
     * Whether one pair of type arguments matches, per the five cases of section 2.4.2.1.
     *
     * <p>Where a case speaks of a type being assignable to a bound, it means assignable as the language has it:
     * a parameterized bound is assignable from the same parameterization, and from a subtype that keeps it, not
     * from any type of the same raw class. And the upper bound of a type variable that is bounded by another
     * variable is that variable's bound, all the way up.</p>
     */
    private static boolean argumentMatches(Type required, Type candidate) {
        if (required instanceof WildcardType wildcard) {
            return withinBounds(candidate, wildcard);
        }
        if (required instanceof TypeVariable<?> requiredVariable) {
            if (candidate instanceof TypeVariable<?> beanVariable) {
                // both are variables: the upper bound of the required one is assignable to the upper bound of the
                // bean one - every bound of the bean variable is satisfied by some bound of the required one
                return boundsSatisfied(uppermostBoundsOf(beanVariable), uppermostBoundsOf(requiredVariable));
            }
            // the specification has no case for a required type variable and an actual bean argument
            return false;
        }
        if (candidate instanceof TypeVariable<?> variable) {
            // an actual required argument matches a variable whose upper bounds it is assignable to
            return assignableToAll(uppermostBoundsOf(variable), required);
        }
        // two actual arguments: the same raw type, and the parameters of a parameterized one matching by these
        // rules again. An argument is not a type: Object here is Object alone, and matches nothing else
        return actualArgumentsMatch(required, candidate);
    }

    private static boolean actualArgumentsMatch(Type required, Type candidate) {
        if (required.equals(candidate)) {
            return true;
        }
        if (isArray(required) || isArray(candidate)) {
            Type requiredComponent = componentOf(required);
            Type candidateComponent = componentOf(candidate);
            return requiredComponent != null && candidateComponent != null
                && actualArgumentsMatch(requiredComponent, candidateComponent);
        }
        Class<?> requiredRaw = rawTypeOf(required);
        if (requiredRaw == null || !requiredRaw.equals(rawTypeOf(candidate))) {
            return false;
        }
        boolean requiredParameterized = required instanceof ParameterizedType;
        boolean candidateParameterized = candidate instanceof ParameterizedType;
        if (requiredParameterized && candidateParameterized) {
            Type[] requiredArguments = ((ParameterizedType) required).getActualTypeArguments();
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
        // one of them raw: the other's parameters must say nothing
        return saysNothing(((ParameterizedType) (requiredParameterized ? required : candidate)).getActualTypeArguments());
    }

    /**
     * Whether the candidate argument fits within the wildcard's bounds: assignable to its upper bound and from
     * its lower bound. A type variable is relaxed, as section 2.4.2.1 relaxes it: its upper bound may be
     * assignable to or from the wildcard's upper bound, and must be assignable from the wildcard's lower bound.
     */
    private static boolean withinBounds(Type candidate, WildcardType wildcard) {
        // an upper bound that is a variable is every bound of that variable at once, so they are resolved; a
        // lower bound that is a variable is the variable, assignable to a type as soon as one of its bounds is
        java.util.List<Type> uppers = uppermostBoundsOf(wildcard.getUpperBounds());
        java.util.List<Type> lowers = java.util.List.of(wildcard.getLowerBounds());
        if (candidate instanceof TypeVariable<?> variable) {
            java.util.List<Type> beanBounds = uppermostBoundsOf(variable);
            if (!boundsSatisfied(uppers, beanBounds) && !boundsSatisfied(beanBounds, uppers)) {
                return false;
            }
            for (Type lower : lowers) {
                if (!assignableToAll(beanBounds, lower)) {
                    return false;
                }
            }
            return true;
        }
        if (!assignableToAll(uppers, candidate)) {
            return false;
        }
        for (Type lower : lowers) {
            if (!isJavaAssignable(candidate, lower)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every bound of the first set is assignable from some bound of the second: what a type within the
     * second set's bounds is then within the first set's as well.
     */
    private static boolean boundsSatisfied(java.util.List<Type> bounds, java.util.List<Type> from) {
        for (Type bound : bounds) {
            boolean satisfied = false;
            for (Type candidate : from) {
                if (isJavaAssignable(bound, candidate)) {
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

    private static boolean assignableToAll(java.util.List<Type> bounds, Type type) {
        for (Type bound : bounds) {
            if (!isJavaAssignable(bound, type)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The upper bounds a type variable resolves to: a bound that is itself a variable stands for its own bounds,
     * all the way up, and a variable without a bound is bounded by {@code Object}.
     */
    private static java.util.List<Type> uppermostBoundsOf(TypeVariable<?> variable) {
        java.util.List<Type> uppermost = uppermostBoundsOf(variable.getBounds());
        return uppermost.isEmpty() ? java.util.List.of(Object.class) : uppermost;
    }

    /**
     * The given bounds with every variable among them replaced by its own uppermost bounds. Empty when there are
     * none, as a wildcard's lower bounds usually are.
     */
    private static java.util.List<Type> uppermostBoundsOf(Type[] bounds) {
        java.util.List<Type> uppermost = new java.util.ArrayList<>(bounds.length);
        for (Type bound : bounds) {
            if (bound instanceof TypeVariable<?> variable) {
                uppermost.addAll(uppermostBoundsOf(variable));
            } else {
                uppermost.add(bound);
            }
        }
        return uppermost;
    }

    /**
     * Whether a value of one type may be assigned to the other, as the language decides it: a class from its
     * subclasses, a parameterized type from the same parameterization of a subtype - an argument that is not a
     * wildcard admitting only itself - a wildcard from what fits its bounds, a variable from what fits all of
     * its bounds and from any of its bounds, and an array from an array of an assignable component.
     */
    private static boolean isJavaAssignable(Type to, Type from) {
        if (to.equals(from)) {
            return true;
        }
        if (from instanceof TypeVariable<?> variable) {
            for (Type bound : uppermostBoundsOf(variable)) {
                if (isJavaAssignable(to, bound)) {
                    return true;
                }
            }
            return false;
        }
        if (from instanceof WildcardType wildcard) {
            for (Type bound : uppermostBoundsOf(wildcard.getUpperBounds())) {
                if (isJavaAssignable(to, bound)) {
                    return true;
                }
            }
            return false;
        }
        if (to instanceof TypeVariable<?> variable) {
            return assignableToAll(uppermostBoundsOf(variable), from);
        }
        if (to instanceof WildcardType wildcard) {
            return withinBounds(from, wildcard);
        }
        if (isArray(to) || isArray(from)) {
            Type toComponent = componentOf(to);
            Type fromComponent = componentOf(from);
            if (toComponent == null || fromComponent == null) {
                return to == Object.class;
            }
            if (toComponent instanceof Class<?> toClass && fromComponent instanceof Class<?> fromClass) {
                return toClass.isAssignableFrom(fromClass);
            }
            return isJavaAssignable(toComponent, fromComponent);
        }
        Class<?> toRaw = rawTypeOf(to);
        Class<?> fromRaw = rawTypeOf(from);
        if (toRaw == null || fromRaw == null || !toRaw.isAssignableFrom(fromRaw)) {
            return false;
        }
        if (!(to instanceof ParameterizedType toParameterized)) {
            return true;
        }
        Type[] toArguments = toParameterized.getActualTypeArguments();
        // the parameterization of the target's class that the source type carries, found among its supertypes
        for (Type supertype : CdiTypes.closureOf(from)) {
            if (!toRaw.equals(rawTypeOf(supertype))) {
                continue;
            }
            if (!(supertype instanceof ParameterizedType fromParameterized)) {
                // a raw source is assignable only to a parameterization that asks nothing of its arguments
                return saysNothing(toArguments);
            }
            Type[] fromArguments = fromParameterized.getActualTypeArguments();
            if (toArguments.length != fromArguments.length) {
                return false;
            }
            for (int i = 0; i < toArguments.length; i++) {
                Type toArgument = toArguments[i];
                Type fromArgument = fromArguments[i];
                // an argument is invariant, a type variable among them: only a wildcard admits other than itself
                boolean assignable = toArgument instanceof WildcardType
                    ? isJavaAssignable(toArgument, fromArgument)
                    : toArgument.equals(fromArgument);
                if (!assignable) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isArray(Type type) {
        return type instanceof java.lang.reflect.GenericArrayType
            || type instanceof Class<?> aClass && aClass.isArray();
    }

    /**
     * The component type of an array type, or {@code null} for a type that is not an array.
     */
    private static @Nullable Type componentOf(Type type) {
        if (type instanceof java.lang.reflect.GenericArrayType array) {
            return array.getGenericComponentType();
        }
        if (type instanceof Class<?> aClass && aClass.isArray()) {
            return aClass.getComponentType();
        }
        return null;
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
