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
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Matches the producers of one class to the disposer methods it declares (section 3.3.7), each disposer described
 * once for the class visit rather than once per producer it is compared with.
 *
 * <p>A disposer disposes of a producer when the type of its disposed parameter is the type the producer produces
 * and their qualifiers are the same, which is the resolution rule of the specification narrowed to the one class
 * the two are declared on. It holds nothing beyond the visit of the class it was made for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class DisposerMatcher {

    /**
     * The qualifiers that take no part in matching a disposer to its producer.
     */
    private static final Set<String> NOT_COMPARED = Cdi.withMicronautQualifiers(
        Cdi.DEFAULT,
        Cdi.ANY
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
            // a disposed parameter qualified Any disposes of what every producer of the type produced, however
            // those producers are qualified; anything else has to be qualified the same way the producer is
            boolean any = disposed.hasDeclaredAnnotation(Cdi.ANY) || disposed.hasDeclaredAnnotation(CdiAny.class);
            candidates.add(new DisposerCandidate(disposer, index, disposed.getGenericType(),
                any ? Map.of() : qualifiers(disposed), any));
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
        Map<String, Map<CharSequence, Object>> producerQualifiers = null;
        DisposerCandidate found = null;
        for (DisposerCandidate candidate : candidates) {
            // section 3.3.7 matches the disposed parameter by the rules of typesafe resolution: a disposer of
            // the supertype disposes of what a producer of the subtype produces — and the type arguments take
            // part, which Micronaut's erased assignability alone would miss
            if (!produced.isAssignable(candidate.disposedType())
                || !typeArgumentsCompatible(produced, candidate.disposedType())) {
                continue;
            }
            if (!candidate.any()) {
                if (producerQualifiers == null) {
                    producerQualifiers = qualifiers(producer);
                }
                if (!candidate.qualifiers().equals(producerQualifiers)) {
                    continue;
                }
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
     * does not dispose of what a producer of {@code List<Integer>} produces, though the two erase alike. A
     * raw side fits any parameterization, and a variable or wildcard on the disposer's side admits what the
     * producer wrote.
     */
    private static boolean typeArgumentsCompatible(ClassElement produced, ClassElement disposed) {
        Collection<ClassElement> producedArguments = produced.getTypeArguments().values();
        Collection<ClassElement> disposedArguments = disposed.getTypeArguments().values();
        if (producedArguments.isEmpty() || disposedArguments.isEmpty()
            || producedArguments.size() != disposedArguments.size()) {
            return true;
        }
        Iterator<ClassElement> producedEach = producedArguments.iterator();
        Iterator<ClassElement> disposedEach = disposedArguments.iterator();
        while (producedEach.hasNext()) {
            ClassElement producedArgument = producedEach.next();
            ClassElement disposedArgument = disposedEach.next();
            if (disposedArgument.isGenericPlaceholder() || disposedArgument.isWildcard()
                || producedArgument.isGenericPlaceholder() || producedArgument.isWildcard()) {
                continue;
            }
            if (!producedArgument.getName().equals(disposedArgument.getName())
                || !typeArgumentsCompatible(producedArgument, disposedArgument)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The qualifiers an element declares, as the names of the qualifier annotations mapped to their members.
     *
     * <p>They are read into a sorted map so that two elements qualified the same way compare equal whichever
     * order the qualifiers were written in. Only the qualifiers the element declares itself are read: the
     * annotation metadata of a member carries what its class declares as well, and a disposer parameter is
     * compared against a producer of another class as often as not.</p>
     *
     * <p>The default qualifier is left out of the comparison, along with the Micronaut annotation that goes with
     * it. It is given to a bean rather than written on one, and a disposer parameter is not a bean, so counting
     * it would have every disposer fail to match the producer it was written for.</p>
     */
    private static Map<String, Map<CharSequence, Object>> qualifiers(Element element) {
        Map<String, Map<CharSequence, Object>> qualifiers = new TreeMap<>();
        for (String name : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (NOT_COMPARED.contains(name) || !element.hasDeclaredAnnotation(name)) {
                continue;
            }
            AnnotationValue<?> annotation = element.getAnnotation(name);
            if (annotation != null) {
                // a member that takes no part in resolution binds a disposer no more than it does a bean, and the
                // list of them is not a member of the annotation
                List<String> nonbinding =
                    Arrays.asList(annotation.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE));
                Map<CharSequence, Object> binding = new TreeMap<>(Comparator.comparing(CharSequence::toString));
                annotation.getValues().forEach((member, value) -> {
                    String memberName = member.toString();
                    if (!memberName.startsWith("$") && !nonbinding.contains(memberName)) {
                        binding.put(memberName, value);
                    }
                });
                qualifiers.put(name, binding);
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
     * @param qualifiers        The qualifiers of its disposed parameter, none where it is qualified {@code Any}
     * @param any               Whether its disposed parameter is qualified {@code Any}
     */
    record DisposerCandidate(MethodElement method, int disposedParameter, ClassElement disposedType,
                             Map<String, Map<CharSequence, Object>> qualifiers, boolean any) {
    }
}
