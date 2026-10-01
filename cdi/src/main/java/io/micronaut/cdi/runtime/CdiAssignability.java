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

import io.micronaut.cdi.runtime.type.SpecificationTypes;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.core.type.WildcardArgument;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * <p>A type is compared as the {@link Argument} that describes it. The types of the specification's API are
 * turned into arguments as they come in, by {@link SpecificationTypes}.</p>
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
        Argument<?> required = SpecificationTypes.argumentOf(requiredType);
        requireNoTypeVariable(required);
        // a type that is not a legal bean type — one with a wildcard in it — is passed over rather than
        // failing the whole set, and matches nothing, not even itself
        if (!isTypeMatching(SpecificationTypes.argumentsOf(beanTypes), required)) {
            return false;
        }
        // the rule of section 2.1.3, applied to the given sets: every bean has Any, and one that names nothing
        // beyond Any and a name has the default qualifier; an injection point that names no qualifier is
        // looking for the default one
        return areQualifiersMatching(CdiQualifier.ofInstances(beanQualifiers),
            CdiQualifier.ofInstances(requiredQualifiers));
    }

    /**
     * Whether a bean with the given qualifiers has every qualifier a lookup requires (section 2.4.2): a bean
     * has {@code Any}, and has {@code Default} where it names no qualifier but a name; a lookup that requires
     * nothing requires {@code Default}.
     *
     * @param beanQualifiers     The qualifiers of the bean
     * @param requiredQualifiers The qualifiers required
     * @return Whether the bean qualifies
     */
    public static boolean areQualifiersMatching(java.util.Collection<CdiQualifier> beanQualifiers,
                                                java.util.Collection<CdiQualifier> requiredQualifiers) {
        boolean namesOne = false;
        for (CdiQualifier qualifier : beanQualifiers) {
            if (!qualifier.isAny() && !qualifier.isNamed()) {
                namesOne = true;
                break;
            }
        }
        if (requiredQualifiers.isEmpty()) {
            return !namesOne || CdiQualifier.DEFAULT.isAmong(beanQualifiers);
        }
        for (CdiQualifier qualifier : requiredQualifiers) {
            if (qualifier.isAny()) {
                continue;
            }
            if (qualifier.isDefault() && !namesOne) {
                continue;
            }
            if (!qualifier.isAmong(beanQualifiers)) {
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
    public static boolean isTypeMatching(Collection<Argument<?>> beanTypes, Argument<?> requiredType) {
        for (Argument<?> beanType : beanTypes) {
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
            if (!CdiQualifier.isQualifierType(qualifier.annotationType())) {
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
        Argument<?> specified = SpecificationTypes.argumentOf(specifiedType);
        requireNoTypeVariable(specified);
        if (CdiTypes.isParameterized(specified)) {
            // an event type is stricter than a required bean type: a type variable anywhere in it leaves the
            // event without a type to be observed as
            for (Argument<?> argument : specified.getTypeParameters()) {
                if (argument.isUnresolvedTypeVariable()) {
                    throw new IllegalArgumentException(
                        "A type variable does not describe an event: " + specifiedType);
                }
            }
        }
        if (!isEventTypeMatching(SpecificationTypes.argumentOf(observedEventType), specified)) {
            return false;
        }
        // the qualifiers an event was fired with always include Any
        return areEventQualifiersMatching(CdiQualifier.ofInstances(specifiedQualifiers),
            CdiQualifier.ofInstances(observedEventQualifiers));
    }

    /**
     * Whether an event fired with the given qualifiers has every qualifier an observer observes
     * (section 2.8.3): an observer of {@code Any} observes whatever the event was fired with, and an event
     * fired with nothing has {@code Default}.
     *
     * @param specifiedQualifiers     The qualifiers the event was fired with
     * @param observedEventQualifiers The qualifiers the observer observes
     * @return Whether the observer observes the event, as far as qualifiers go
     */
    public static boolean areEventQualifiersMatching(java.util.Collection<CdiQualifier> specifiedQualifiers,
                                                     java.util.Collection<CdiQualifier> observedEventQualifiers) {
        for (CdiQualifier observed : observedEventQualifiers) {
            if (observed.isAny()) {
                continue;
            }
            if (specifiedQualifiers.isEmpty() && observed.isDefault()) {
                continue;
            }
            if (!observed.isAmong(specifiedQualifiers)) {
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
    public static boolean isEventTypeMatching(Argument<?> observedEventType, Argument<?> eventType) {
        for (Argument<?> type : typeClosureOf(eventType)) {
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
    static boolean isLegalBeanType(Argument<?> type) {
        if (type.isWildcard()) {
            return false;
        }
        if (CdiTypes.isParameterized(type)) {
            for (Argument<?> argument : type.getTypeParameters()) {
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
    private static List<Argument<?>> typeClosureOf(Argument<?> type) {
        List<Argument<?>> closure = new ArrayList<>(CdiTypes.closureOf(type));
        // every type's closure ends in Object, which collecting skips so that the many chains that all end
        // there contribute it only once
        closure.add(Argument.OBJECT_ARGUMENT);
        return closure;
    }

    /**
     * Whether an event of the given type notifies an observer of the observed type, the way section 2.8.3 has
     * it: the observed type is a supertype of the event type — real subtyping, unlike the matching of bean
     * types, because an observer of the supertype hears the events of every subtype.
     */
    private static boolean isEventAssignable(Argument<?> observed, Argument<?> event) {
        if (observed.equalsStructure(event)) {
            return true;
        }
        if (observed.isUnresolvedTypeVariable()) {
            // an observed type variable observes whatever fits its bounds
            return assignableToAll(boundsAgainst(observed, event), event);
        }
        if (CdiTypes.isArray(observed) || CdiTypes.isArray(event)) {
            // arrays are observed by their components, as the language assigns them: covariantly for classes,
            // with no boxing, and by these rules again for a parameterized component
            Argument<?> observedComponent = observed.componentType();
            Argument<?> eventComponent = event.componentType();
            if (observedComponent == null || eventComponent == null) {
                return CdiTypes.isObject(observed);
            }
            if (CdiTypes.isClass(observedComponent) && CdiTypes.isClass(eventComponent)) {
                return observedComponent.getType().isAssignableFrom(eventComponent.getType());
            }
            // a parameterized component is judged against the entry of its closure of the observed raw type
            return isEventTypeMatching(observedComponent, eventComponent);
        }
        Class<?> observedRaw = rawTypeOf(observed);
        Class<?> eventRaw = rawTypeOf(event);
        if (observedRaw == null || eventRaw == null || !observedRaw.isAssignableFrom(eventRaw)) {
            return false;
        }
        if (CdiTypes.isParameterized(observed)) {
            if (!CdiTypes.isParameterized(event)) {
                return saysNothing(observed.getTypeParameters());
            }
            if (observedRaw != eventRaw) {
                // not the comparable pair: the arguments of a subtype say nothing of those of its supertype,
                // which the event's closure carries as its own entry, of the observed raw type, and that one is
                // what the observed type is judged against (section 9.3.1)
                return false;
            }
            return allMatch(observed.getTypeParameters(), event.getTypeParameters(), true);
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
    private static boolean isAssignable(Argument<?> required, Argument<?> candidate) {
        if (required.equalsStructure(candidate)) {
            return true;
        }
        if (CdiTypes.isArray(required) || CdiTypes.isArray(candidate)) {
            // two array types match when their element types do, by these rules again; a class component is the
            // same type or not, with no boxing, since an int[] is not an Integer[]
            Argument<?> requiredComponent = required.componentType();
            Argument<?> candidateComponent = candidate.componentType();
            if (requiredComponent == null || candidateComponent == null) {
                return CdiTypes.isObject(required);
            }
            if (CdiTypes.isClass(requiredComponent) && CdiTypes.isClass(candidateComponent)) {
                return requiredComponent.getType().equals(candidateComponent.getType());
            }
            return isAssignable(requiredComponent, candidateComponent);
        }
        Class<?> requiredRaw = rawTypeOf(required);
        Class<?> candidateRaw = rawTypeOf(candidate);
        if (requiredRaw == null || candidateRaw == null) {
            // a type variable or a wildcard names no type of its own, and is passed over
            return false;
        }
        boolean requiredParameterized = CdiTypes.isParameterized(required);
        boolean candidateParameterized = CdiTypes.isParameterized(candidate);
        if (requiredRaw == Object.class && !requiredParameterized) {
            return true;
        }
        if (!CdiTypes.boxedOf(requiredRaw).equals(CdiTypes.boxedOf(candidateRaw))) {
            return false;
        }
        if (!requiredParameterized && !candidateParameterized) {
            return true;
        }
        if (!requiredParameterized) {
            // a parameterized bean type matches the raw required type when its own parameters say nothing:
            // unbounded variables, or Object
            return saysNothingOfABean(candidate.getTypeParameters());
        }
        if (!candidateParameterized) {
            // and a raw bean type matches a parameterized required type on the same terms, which do not
            // include a wildcard (section 2.4.2.4): a raw Box is no Box<?>
            return saysNothingOfABean(required.getTypeParameters());
        }
        return allMatch(required.getTypeParameters(), candidate.getTypeParameters(), false);
    }

    /**
     * Whether two lists of type arguments match pair by pair, as event type arguments or as bean type arguments.
     */
    private static boolean allMatch(Argument<?>[] required, Argument<?>[] candidate, boolean event) {
        if (required.length != candidate.length) {
            return false;
        }
        for (int i = 0; i < required.length; i++) {
            if (event ? !eventArgumentMatches(required[i], candidate[i]) : !argumentMatches(required[i], candidate[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one pair of event type arguments matches, per the cases of section 2.8.3: the observed side may
     * name a wildcard or a variable, and either admits what fits its bounds.
     */
    private static boolean eventArgumentMatches(Argument<?> observed, Argument<?> event) {
        if (observed instanceof WildcardArgument<?> wildcard) {
            return withinBounds(event, wildcard);
        }
        if (observed.isUnresolvedTypeVariable()) {
            // the event type parameter is assignable to the upper bound of the observed variable
            return assignableToAll(boundsAgainst(observed, event), event);
        }
        if (observed.equalsStructure(event)) {
            return true;
        }
        Class<?> observedRaw = rawTypeOf(observed);
        Class<?> eventRaw = rawTypeOf(event);
        if (observedRaw == null || !observedRaw.equals(eventRaw)) {
            return false;
        }
        if (CdiTypes.isParameterized(observed) && CdiTypes.isParameterized(event)) {
            return allMatch(observed.getTypeParameters(), event.getTypeParameters(), true);
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
    private static boolean argumentMatches(Argument<?> required, Argument<?> candidate) {
        if (required instanceof WildcardArgument<?> wildcard) {
            return withinBounds(candidate, wildcard);
        }
        if (required.isUnresolvedTypeVariable()) {
            if (candidate.isUnresolvedTypeVariable()) {
                // both are variables: the upper bound of the required one is assignable to the upper bound of the
                // bean one - every bound of the bean variable is satisfied by some bound of the required one
                return boundsSatisfied(uppermostBoundsOf(candidate), uppermostBoundsOf(required));
            }
            // the specification has no case for a required type variable and an actual bean argument
            return false;
        }
        if (candidate.isUnresolvedTypeVariable()) {
            // an actual required argument matches a variable whose upper bounds it is assignable to
            return assignableToAll(boundsAgainst(candidate, required), required);
        }
        // two actual arguments: the same raw type, and the parameters of a parameterized one matching by these
        // rules again. An argument is not a type: Object here is Object alone, and matches nothing else
        return actualArgumentsMatch(required, candidate);
    }

    private static boolean actualArgumentsMatch(Argument<?> required, Argument<?> candidate) {
        if (required.equalsStructure(candidate)) {
            return true;
        }
        if (CdiTypes.isArray(required) || CdiTypes.isArray(candidate)) {
            Argument<?> requiredComponent = required.componentType();
            Argument<?> candidateComponent = candidate.componentType();
            return requiredComponent != null && candidateComponent != null
                && actualArgumentsMatch(requiredComponent, candidateComponent);
        }
        Class<?> requiredRaw = rawTypeOf(required);
        if (requiredRaw == null || !requiredRaw.equals(rawTypeOf(candidate))) {
            return false;
        }
        boolean requiredParameterized = CdiTypes.isParameterized(required);
        boolean candidateParameterized = CdiTypes.isParameterized(candidate);
        if (requiredParameterized && candidateParameterized) {
            return allMatch(required.getTypeParameters(), candidate.getTypeParameters(), false);
        }
        // one of them raw: the other's parameters must say nothing
        return saysNothingOfABean((requiredParameterized ? required : candidate).getTypeParameters());
    }

    /**
     * Whether the candidate argument fits within the wildcard's bounds: assignable to its upper bound and from
     * its lower bound. A type variable is relaxed, as section 2.4.2.1 relaxes it: its upper bound may be
     * assignable to or from the wildcard's upper bound, and must be assignable from the wildcard's lower bound.
     */
    private static boolean withinBounds(Argument<?> candidate, WildcardArgument<?> wildcard) {
        // an upper bound that is a variable is every bound of that variable at once, so they are resolved; a
        // lower bound that is a variable is the variable, assignable to a type as soon as one of its bounds is
        List<Argument<?>> uppers = uppermostBoundsOf(wildcard.getUpperBounds());
        List<Argument<?>> lowers = wildcard.getLowerBounds();
        if (candidate.isUnresolvedTypeVariable()) {
            List<Argument<?>> beanBounds = uppermostBoundsOf(candidate);
            if (!boundsSatisfied(uppers, beanBounds) && !boundsSatisfied(beanBounds, uppers)) {
                return false;
            }
            for (Argument<?> lower : lowers) {
                if (!assignableToAll(beanBounds, lower)) {
                    return false;
                }
            }
            return true;
        }
        if (!assignableToAll(uppers, candidate)) {
            return false;
        }
        for (Argument<?> lower : lowers) {
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
    private static boolean boundsSatisfied(List<Argument<?>> bounds, List<Argument<?>> from) {
        for (Argument<?> bound : bounds) {
            boolean satisfied = false;
            for (Argument<?> candidate : from) {
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

    private static boolean assignableToAll(List<Argument<?>> bounds, Argument<?> type) {
        for (Argument<?> bound : bounds) {
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
    private static List<Argument<?>> uppermostBoundsOf(Argument<?> variable) {
        return uppermostBoundsOf(((GenericPlaceholder<?>) variable).getBounds());
    }

    /**
     * The uppermost bounds of a variable, to check a type against: a bound that names the variable itself - the
     * {@code Comparable<T>} of {@code T extends Comparable<T>} - names the type checked, the way the language
     * checks a bound.
     */
    private static List<Argument<?>> boundsAgainst(Argument<?> variable, Argument<?> type) {
        List<Argument<?>> bounds = uppermostBoundsOf(variable);
        Map<String, Argument<?>> itself = Map.of(((GenericPlaceholder<?>) variable).getVariableName(), type);
        List<Argument<?>> substituted = new ArrayList<>(bounds.size());
        for (Argument<?> bound : bounds) {
            substituted.add(CdiTypes.substitute(bound, itself, false));
        }
        return substituted;
    }

    /**
     * The given bounds with every variable among them replaced by its own uppermost bounds. Empty when there are
     * none, as a wildcard's lower bounds usually are.
     */
    private static List<Argument<?>> uppermostBoundsOf(List<Argument<?>> bounds) {
        List<Argument<?>> uppermost = new ArrayList<>(bounds.size());
        for (Argument<?> bound : bounds) {
            if (bound.isUnresolvedTypeVariable()) {
                uppermost.addAll(uppermostBoundsOf(bound));
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
    private static boolean isJavaAssignable(Argument<?> to, Argument<?> from) {
        if (to.equalsStructure(from)) {
            return true;
        }
        if (from.isUnresolvedTypeVariable()) {
            for (Argument<?> bound : uppermostBoundsOf(from)) {
                if (isJavaAssignable(to, bound)) {
                    return true;
                }
            }
            return false;
        }
        if (from instanceof WildcardArgument<?> wildcard) {
            for (Argument<?> bound : uppermostBoundsOf(wildcard.getUpperBounds())) {
                if (isJavaAssignable(to, bound)) {
                    return true;
                }
            }
            return false;
        }
        if (to.isUnresolvedTypeVariable()) {
            return assignableToAll(boundsAgainst(to, from), from);
        }
        if (to instanceof WildcardArgument<?> wildcard) {
            return withinBounds(from, wildcard);
        }
        if (CdiTypes.isArray(to) || CdiTypes.isArray(from)) {
            Argument<?> toComponent = to.componentType();
            Argument<?> fromComponent = from.componentType();
            if (toComponent == null || fromComponent == null) {
                return CdiTypes.isObject(to);
            }
            if (CdiTypes.isClass(toComponent) && CdiTypes.isClass(fromComponent)) {
                return toComponent.getType().isAssignableFrom(fromComponent.getType());
            }
            return isJavaAssignable(toComponent, fromComponent);
        }
        Class<?> toRaw = rawTypeOf(to);
        Class<?> fromRaw = rawTypeOf(from);
        if (toRaw == null || fromRaw == null || !toRaw.isAssignableFrom(fromRaw)) {
            return false;
        }
        if (!CdiTypes.isParameterized(to)) {
            return true;
        }
        Argument<?>[] toArguments = to.getTypeParameters();
        // the parameterization of the target's class that the source type carries, found among its supertypes
        for (Argument<?> supertype : CdiTypes.closureOf(from)) {
            if (!toRaw.equals(rawTypeOf(supertype))) {
                continue;
            }
            if (!CdiTypes.isParameterized(supertype)) {
                // a raw source is assignable only to a parameterization that asks nothing of its arguments
                return saysNothing(toArguments);
            }
            Argument<?>[] fromArguments = supertype.getTypeParameters();
            if (toArguments.length != fromArguments.length) {
                return false;
            }
            for (int i = 0; i < toArguments.length; i++) {
                Argument<?> toArgument = toArguments[i];
                Argument<?> fromArgument = fromArguments[i];
                // an argument is invariant, a type variable among them: only a wildcard admits other than itself
                boolean assignable = toArgument.isWildcard()
                    ? isJavaAssignable(toArgument, fromArgument)
                    : toArgument.equalsStructure(fromArgument);
                if (!assignable) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Whether the arguments of a parameterized type say nothing at all: every one an unbounded variable, an
     * unbounded wildcard, or {@code Object}.
     */
    private static boolean saysNothing(Argument<?>[] arguments) {
        for (Argument<?> argument : arguments) {
            if (CdiTypes.isObject(argument)) {
                continue;
            }
            if (argument.isUnresolvedTypeVariable()) {
                if (onlyObject(((GenericPlaceholder<?>) argument).getBounds())) {
                    continue;
                }
                return false;
            }
            if (argument instanceof WildcardArgument<?> wildcard) {
                if (wildcard.getLowerBounds().isEmpty() && onlyObject(wildcard.getUpperBounds())) {
                    continue;
                }
                return false;
            }
            return false;
        }
        return true;
    }

    /**
     * Whether the arguments of a parameterized type say nothing by the rules of section 2.4.2.4, which match a
     * raw type with a parameterized one: every one an unbounded variable or {@code Object}. Unlike the
     * language's assignability, an unbounded wildcard is not among them.
     */
    private static boolean saysNothingOfABean(Argument<?>[] arguments) {
        for (Argument<?> argument : arguments) {
            if (argument.isWildcard()) {
                return false;
            }
        }
        return saysNothing(arguments);
    }

    private static boolean onlyObject(List<Argument<?>> bounds) {
        return bounds.isEmpty() || bounds.size() == 1 && CdiTypes.isObject(bounds.get(0));
    }

    /**
     * The class of a class or of a parameterized type, or {@code null} for a type that is neither.
     */
    private static @Nullable Class<?> rawTypeOf(Argument<?> type) {
        return CdiTypes.classOf(type);
    }

    static void requireNoTypeVariable(Argument<?> type) {
        // a parameterized type may carry type variables among its arguments — section 2.4.2.1 has rules for
        // matching them — but a bare type variable names nothing to resolve
        if (type.isUnresolvedTypeVariable()) {
            throw new IllegalArgumentException("A type variable does not describe a bean or an event: "
                + SpecificationTypes.typeOf(type).getTypeName());
        }
    }
}
