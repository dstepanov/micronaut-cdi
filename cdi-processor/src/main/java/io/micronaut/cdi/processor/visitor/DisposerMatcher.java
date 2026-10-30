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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.cdi.internal.metadata.CdiAny;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Matches the producers of one class to the disposer methods it declares (section 3.3.7), each disposer described
 * once for the class visit rather than once per producer it is compared with.
 *
 * <p>A disposer disposes of a producer by the rules of typesafe resolution (section 3.4.3) narrowed to the one class
 * the two are declared on: the type of its disposed parameter is a bean type of the producer, and the producer has
 * every qualifier the parameter requires. It holds nothing beyond the visit of the class it was made for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class DisposerMatcher {

    private static final String NAMED = "jakarta.inject.Named";
    private static final Set<String> EVERY_ARRAY = Set.of(Object.class.getName(), Cloneable.class.getName(),
        java.io.Serializable.class.getName());

    /**
     * The qualifiers that are not compared as values: {@code Default} and {@code Any}, which are given rather than
     * compared, and Micronaut's own.
     */
    private static final Set<String> NOT_COMPARED = Cdi.withMicronautQualifiers(
        Cdi.DEFAULT,
        Cdi.ANY,
        CdiAny.class.getName()
    );

    private final List<DisposerCandidate> candidates;

    private DisposerMatcher(List<DisposerCandidate> candidates) {
        this.candidates = candidates;
    }

    /**
     * Describes the disposers of a class, in the order the class declares them.
     *
     * @param disposers The disposer methods, each with a disposed parameter
     * @return The matcher
     */
    static DisposerMatcher of(List<MethodElement> disposers) {
        List<DisposerCandidate> candidates = new ArrayList<>(disposers.size());
        for (MethodElement disposer : disposers) {
            int index = disposedParameter(disposer);
            ParameterElement disposed = disposer.getParameters()[index];
            List<AnnotationValue<?>> required = qualifiers(disposed);
            // a parameter that declares no qualifier requires the default one (section 2.3.4); one qualified Any
            // requires nothing of a producer beyond the other qualifiers it declares
            boolean any = isAny(disposed);
            boolean requiresDefault = disposed.hasDeclaredAnnotation(Cdi.DEFAULT) || (required.isEmpty() && !any);
            candidates.add(new DisposerCandidate(disposer, index, disposed.getGenericType(), required,
                requiresDefault));
        }
        return new DisposerMatcher(List.copyOf(candidates));
    }

    /**
     * Finds the disposer of a producer, failing the compilation where more than one disposes of what it produces.
     *
     * @param producer The producer, its metadata prepared
     * @param context  The compilation
     * @return The disposer, or {@code null} where none disposes of it, or more than one does
     */
    @Nullable DisposerCandidate find(MemberElement producer, VisitorContext context) {
        ClassElement produced = ProducerVisitor.producedType(producer);
        List<AnnotationValue<?>> producerQualifiers = null;
        DisposerCandidate found = null;
        for (DisposerCandidate candidate : candidates) {
            // section 3.3.7 matches the disposed parameter by the rules of typesafe resolution: a disposer of
            // the supertype disposes of what a producer of the subtype produces — and the type arguments take
            // part, which Micronaut's erased assignability alone would miss
            if (!isAssignableToDisposed(produced, candidate.disposedType())) {
                continue;
            }
            if (producerQualifiers == null) {
                producerQualifiers = qualifiers(producer);
            }
            if (!hasRequiredQualifiers(candidate, producer, producerQualifiers)) {
                continue;
            }
            if (found != null) {
                context.fail("More than one disposer method disposes of what this producer produces", producer);
                return null;
            }
            found = candidate;
        }
        return found;
    }

    /**
     * Whether the producer has every qualifier the disposed parameter requires: the default one where the parameter
     * requires it - a producer has it where it declares it, or declares no qualifier but {@code Named} and
     * {@code Any} - and each other one, compared by its binding members as Micronaut compares two occurrences
     * ({@link AnnotationValue#matches}), defaults filled in and arrays compared by their elements.
     */
    private static boolean hasRequiredQualifiers(DisposerCandidate candidate, MemberElement producer,
                                                 List<AnnotationValue<?>> producerQualifiers) {
        if (candidate.requiresDefault() && !hasDefault(producer, producerQualifiers)) {
            return false;
        }
        for (AnnotationValue<?> required : candidate.qualifiers()) {
            boolean present = false;
            for (AnnotationValue<?> qualifier : producerQualifiers) {
                if (qualifier.getAnnotationName().equals(required.getAnnotationName()) && qualifier.matches(required)) {
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

    private static boolean hasDefault(Element producer, List<AnnotationValue<?>> qualifiers) {
        if (producer.hasDeclaredAnnotation(Cdi.DEFAULT)) {
            return true;
        }
        for (AnnotationValue<?> qualifier : qualifiers) {
            if (!NAMED.equals(qualifier.getAnnotationName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAny(Element element) {
        return element.hasDeclaredAnnotation(Cdi.ANY) || element.hasDeclaredAnnotation(CdiAny.class);
    }

    /**
     * The index of the first disposed parameter of a disposer.
     */
    private static int disposedParameter(MethodElement disposer) {
        ParameterElement[] parameters = disposer.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].hasDeclaredAnnotation(Cdi.DISPOSES)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a bean type of what the producer produces is assignable to the disposed parameter's type by the rules
     * of typesafe resolution (section 2.4.2), the produced type first read as the disposed parameter's class: a class
     * that swaps the two arguments of the map it extends, produced as {@code Flipped<String, Integer>}, is a
     * {@code Map<Integer, String>}, and a class with no arguments of its own is the parameterization of the supertype it
     * extends. An array producer has its own array type and {@code Object} as its bean types (section 3.3.2), and an
     * array matches only an array of identical element type.
     */
    static boolean isAssignableToDisposed(ClassElement produced, ClassElement disposed) {
        if (isObject(disposed)) {
            return true;
        }
        if (produced.isArray() || disposed.isArray()) {
            return produced.isArray() && disposed.isArray() && isIdentical(produced, disposed);
        }
        if (!produced.isAssignable(disposed.getName())) {
            return false;
        }
        Collection<ClassElement> required = argumentsOf(disposed);
        Collection<ClassElement> bean = produced.getName().equals(disposed.getName())
            ? argumentsOf(produced) : produced.getTypeArguments(disposed.getName()).values();
        if (produced.isRawType() && produced.getName().equals(disposed.getName())) {
            bean = List.of();
        }
        if (required.isEmpty()) {
            // a parameterized bean type is a raw required type where its arguments ask nothing
            return bean.stream().allMatch(DisposerMatcher::asksNothing);
        }
        if (bean.isEmpty()) {
            // a raw bean type is a parameterized required type where the required arguments ask nothing
            return required.stream().allMatch(DisposerMatcher::asksNothing);
        }
        if (bean.size() != required.size()) {
            return false;
        }
        Iterator<ClassElement> beanEach = bean.iterator();
        for (ClassElement requiredArgument : required) {
            if (!isArgumentAssignable(beanEach.next(), requiredArgument)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a type argument of the bean type is assignable to one of the required type (section 2.4.2.1).
     */
    private static boolean isArgumentAssignable(ClassElement bean, ClassElement required) {
        if (required instanceof WildcardElement wildcard) {
            return withinBounds(bean, wildcard.getUpperBounds(), wildcard.getLowerBounds());
        }
        if (required instanceof GenericPlaceholderElement variable) {
            return withinBounds(bean, variable.getBounds(), List.of());
        }
        if (bean instanceof GenericPlaceholderElement variable) {
            // an actual required type is assignable to a variable of the bean type within the bounds of the variable
            for (ClassElement bound : variable.getBounds()) {
                if (!isJavaAssignable(required, bound)) {
                    return false;
                }
            }
            return true;
        }
        return isIdentical(bean, required);
    }

    /**
     * Whether a type argument of the bean type is within the given bounds: an actual type assignable to every upper
     * bound and with every lower bound assignable to it, a variable by its upper bounds.
     */
    private static boolean withinBounds(ClassElement argument, List<? extends ClassElement> upper,
                                        List<? extends ClassElement> lower) {
        List<? extends ClassElement> own = argument instanceof GenericPlaceholderElement variable
            ? variable.getBounds() : List.of(argument);
        for (ClassElement bound : upper) {
            if (isObject(bound)) {
                continue;
            }
            boolean any = false;
            for (ClassElement type : own) {
                any |= isJavaAssignable(type, bound);
            }
            if (!any) {
                return false;
            }
        }
        for (ClassElement bound : lower) {
            if (argument instanceof GenericPlaceholderElement || !isJavaAssignable(bound, argument)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one type is assignable to another as Java has it, with type arguments compared: what a bound asks of
     * a type.
     */
    private static boolean isJavaAssignable(ClassElement from, ClassElement to) {
        if (isObject(to)) {
            return true;
        }
        if (from instanceof GenericPlaceholderElement variable) {
            for (ClassElement bound : variable.getBounds()) {
                if (isJavaAssignable(bound, to)) {
                    return true;
                }
            }
            return false;
        }
        if (to instanceof GenericPlaceholderElement || to.isWildcard()) {
            return true;
        }
        if (from.isArray() || to.isArray()) {
            if (from.isArray() && to.isArray() && from.getArrayDimensions() == to.getArrayDimensions()) {
                return from.isPrimitive() || to.isPrimitive() ? isIdentical(from, to)
                    : isJavaAssignable(from.fromArray(), to.fromArray());
            }
            return from.isArray() && !to.isArray() && EVERY_ARRAY.contains(to.getName());
        }
        if (!from.isAssignable(to.getName())) {
            return false;
        }
        Collection<ClassElement> required = argumentsOf(to);
        if (required.isEmpty()) {
            return true;
        }
        Collection<ClassElement> projected = from.getName().equals(to.getName())
            ? argumentsOf(from) : from.getTypeArguments(to.getName()).values();
        if (projected.isEmpty() || projected.size() != required.size()) {
            // a raw type is assignable to any parameterization of itself, unchecked
            return true;
        }
        Iterator<ClassElement> projectedEach = projected.iterator();
        for (ClassElement requiredArgument : required) {
            ClassElement argument = projectedEach.next();
            boolean fits = requiredArgument instanceof WildcardElement wildcard
                ? withinBounds(argument, wildcard.getUpperBounds(), wildcard.getLowerBounds())
                : requiredArgument instanceof GenericPlaceholderElement || isIdentical(argument, requiredArgument);
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two types are the same type: the same class, as many dimensions, and identical arguments.
     */
    private static boolean isIdentical(ClassElement one, ClassElement other) {
        if (one instanceof GenericPlaceholderElement a && other instanceof GenericPlaceholderElement b) {
            return a.getVariableName().equals(b.getVariableName());
        }
        if (one instanceof WildcardElement a && other instanceof WildcardElement b) {
            return areIdentical(a.getUpperBounds(), b.getUpperBounds())
                && areIdentical(a.getLowerBounds(), b.getLowerBounds());
        }
        if (!one.getName().equals(other.getName()) || one.getArrayDimensions() != other.getArrayDimensions()) {
            return false;
        }
        List<ClassElement> oneArguments = List.copyOf(argumentsOf(one));
        List<ClassElement> otherArguments = List.copyOf(argumentsOf(other));
        if (oneArguments.isEmpty() || otherArguments.isEmpty()) {
            return oneArguments.isEmpty() && otherArguments.isEmpty();
        }
        return areIdentical(oneArguments, otherArguments);
    }

    private static boolean areIdentical(List<? extends ClassElement> one, List<? extends ClassElement> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < one.size(); i++) {
            if (!isIdentical(one.get(i), other.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The type arguments of a type as written, none for a raw use or a type that takes none.
     */
    private static Collection<ClassElement> argumentsOf(ClassElement type) {
        return type.isRawType() ? List.of() : type.getTypeArguments().values();
    }

    /**
     * Whether a type argument asks nothing: {@code Object}, or an unbounded variable.
     */
    private static boolean asksNothing(ClassElement argument) {
        if (argument instanceof GenericPlaceholderElement variable) {
            return variable.getBounds().stream().allMatch(DisposerMatcher::isObject);
        }
        return isObject(argument);
    }

    private static boolean isObject(ClassElement type) {
        return !type.isArray() && Object.class.getName().equals(type.getName());
    }

    /**
     * The qualifiers an element declares itself, every occurrence of a repeatable one among them, but for
     * {@code Default}, {@code Any} and Micronaut's own (see {@link #NOT_COMPARED}): the annotation metadata of a
     * member carries what its class declares as well, and a disposer parameter is compared against a producer of
     * another class as often as not.
     */
    private static List<AnnotationValue<?>> qualifiers(Element element) {
        List<AnnotationValue<?>> qualifiers = new ArrayList<>();
        for (String name : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (NOT_COMPARED.contains(name) || !element.hasDeclaredAnnotation(name)) {
                continue;
            }
            List<AnnotationValue<Annotation>> occurrences =
                element.getAnnotationMetadata().getDeclaredAnnotationValuesByName(name);
            if (occurrences.isEmpty()) {
                AnnotationValue<Annotation> single = element.getAnnotation(name);
                if (single != null) {
                    qualifiers.add(single);
                }
            } else {
                qualifiers.addAll(occurrences);
            }
        }
        return qualifiers;
    }

    /**
     * One disposer method of the class, described for matching.
     *
     * @param method            The disposer method
     * @param disposedParameter The index of its disposed parameter
     * @param disposedType      The type of its disposed parameter
     * @param qualifiers        The qualifiers its disposed parameter requires of a producer beside the default one
     * @param requiresDefault   Whether its disposed parameter requires the default qualifier
     */
    record DisposerCandidate(MethodElement method, int disposedParameter, ClassElement disposedType,
                             List<AnnotationValue<?>> qualifiers, boolean requiresDefault) {
    }
}
