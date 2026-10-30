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
import java.util.Map;
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
            if (!produced.isAssignable(candidate.disposedType())
                || !typeArgumentsCompatible(produced, candidate.disposedType())) {
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
     * Whether the produced type's arguments fit the disposed parameter's: a disposer of {@code List<String>}
     * does not dispose of what a producer of {@code List<Integer>} produces, though the two erase alike. The
     * produced type is first read as the disposed parameter's class - a class that swaps the two arguments of the
     * map it extends, produced as {@code Flipped<String, Integer>}, is a {@code Map<Integer, String>}, and a class
     * with no arguments of its own is the parameterization of the supertype it extends. A raw side fits any parameterization, a
     * variable on either side admits what the other wrote, and a wildcard on the disposer's side admits what is
     * within its bounds.
     */
    private static boolean typeArgumentsCompatible(ClassElement produced, ClassElement disposed) {
        Collection<ClassElement> disposedArguments = disposed.getTypeArguments().values();
        if (disposedArguments.isEmpty()) {
            return true;
        }
        Map<String, ClassElement> projected = produced.getName().equals(disposed.getName())
            ? produced.getTypeArguments() : produced.getTypeArguments(disposed.getName());
        Collection<ClassElement> producedArguments = projected.values();
        if (producedArguments.isEmpty() || producedArguments.size() != disposedArguments.size()) {
            return true;
        }
        Iterator<ClassElement> producedEach = producedArguments.iterator();
        Iterator<ClassElement> disposedEach = disposedArguments.iterator();
        while (producedEach.hasNext()) {
            ClassElement producedArgument = producedEach.next();
            ClassElement disposedArgument = disposedEach.next();
            if (producedArgument.isGenericPlaceholder() || producedArgument.isWildcard()
                || disposedArgument.isGenericPlaceholder()) {
                continue;
            }
            if (disposedArgument instanceof WildcardElement wildcard) {
                if (!withinBounds(producedArgument, wildcard)) {
                    return false;
                }
                continue;
            }
            if (!producedArgument.getName().equals(disposedArgument.getName())
                || producedArgument.getArrayDimensions() != disposedArgument.getArrayDimensions()
                || !typeArgumentsCompatible(producedArgument, disposedArgument)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinBounds(ClassElement argument, WildcardElement wildcard) {
        for (ClassElement upper : wildcard.getUpperBounds()) {
            if (!upper.getName().equals(Object.class.getName()) && !argument.isAssignable(upper)) {
                return false;
            }
        }
        for (ClassElement lower : wildcard.getLowerBounds()) {
            if (!lower.isAssignable(argument)) {
                return false;
            }
        }
        return true;
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
