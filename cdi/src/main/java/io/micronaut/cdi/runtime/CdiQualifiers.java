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

import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the qualifiers of the specification as the Micronaut qualifiers they were compiled into, and back again.
 *
 * <p>A qualifier is an annotation on both sides, and Micronaut compares two of them the way the specification says
 * to: by the annotation type and by the members that are not excluded from the comparison. What the two sides do
 * not share is the shape they are asked for in. The specification hands a program annotation instances, because
 * that is what its own interfaces take and return; Micronaut resolves a bean by a {@link Qualifier}. This turns
 * one into the other.</p>
 *
 * <p>The two built-in qualifiers are the exceptions. {@code Any} is every bean, which Micronaut has a qualifier of
 * its own for, and asking for nothing at all is asking for {@code Default}, which is the rule of section 2.2.8 of
 * the specification read from the other end.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiQualifiers {

    private CdiQualifiers() {
    }

    /**
     * The Micronaut qualifier that resolves the beans the given qualifiers of the specification do.
     *
     * @param qualifiers The qualifier annotations, which may be empty
     * @param <T>        The bean type
     * @return The qualifier, or {@code null} when every bean of the type qualifies
     */
    public static <T> @Nullable Qualifier<T> of(Annotation... qualifiers) {
        return of(CdiQualifier.ofInstances(qualifiers));
    }

    /**
     * The Micronaut qualifier that resolves the beans the given qualifiers do.
     *
     * @param qualifiers The qualifiers, which may be empty
     * @param <T>        The bean type
     * @return The qualifier, or {@code null} when every bean of the type qualifies
     */
    public static <T> @Nullable Qualifier<T> of(java.util.Collection<CdiQualifier> qualifiers) {
        if (qualifiers.isEmpty()) {
            // a lookup that names no qualifier is looking for the default one
            return Qualifiers.byAnnotation(Default.Literal.INSTANCE);
        }
        List<Qualifier<T>> resolved = new ArrayList<>(qualifiers.size());
        for (CdiQualifier qualifier : qualifiers) {
            if (qualifier.isAny()) {
                // every bean has the Any qualifier, so asking for it narrows nothing
                continue;
            }
            if (qualifier.isNamed()) {
                // a name is how Micronaut qualifies a bean of its own accord, and it has a qualifier for it
                resolved.add(Qualifiers.byName(qualifier.binding().stringValue().orElse("")));
                continue;
            }
            // built from the values the annotation was written with: the member of a qualifier takes part in
            // the comparison, and section 2.4.2 has a bean qualified @Chunky(true) not resolving an injection
            // point that asks for @Chunky(false)
            @SuppressWarnings("unchecked")
            Qualifier<T> byValues = (Qualifier<T>) Qualifiers.byAnnotation(
                AnnotationMetadata.EMPTY_METADATA, qualifier.binding());
            resolved.add(byValues);
        }
        if (resolved.isEmpty()) {
            return null;
        }
        if (resolved.size() == 1) {
            return resolved.get(0);
        }
        @SuppressWarnings("unchecked")
        Qualifier<T>[] array = resolved.toArray(new Qualifier[0]);
        return Qualifiers.byQualifiers(array);
    }

    /**
     * The Micronaut qualifier that resolves the beans the given qualifiers do, from the values the qualifiers
     * were recorded with rather than from annotation instances: what a synthetic bean is qualified by was
     * recorded while the application compiled, and nothing has to be read back to resolve by it.
     *
     * @param qualifiers The qualifiers, each as the values it was written with
     * @param nonbinding The members that take no part in resolution, each as {@code annotationName#memberName}
     * @param <T>        The bean type
     * @return The qualifier, or {@code null} when every bean of the type qualifies
     */
    public static <T> @Nullable Qualifier<T> ofValues(List<? extends AnnotationValue<?>> qualifiers,
                                                      Set<String> nonbinding) {
        List<Qualifier<T>> resolved = new ArrayList<>(qualifiers.size());
        for (AnnotationValue<?> qualifier : qualifiers) {
            String name = qualifier.getAnnotationName();
            if ("jakarta.enterprise.inject.Any".equals(name)) {
                continue;
            }
            if ("jakarta.inject.Named".equals(name)) {
                resolved.add(Qualifiers.byName(qualifier.stringValue().orElse("")));
                continue;
            }
            Map<CharSequence, Object> binding = new LinkedHashMap<>(qualifier.getValues());
            binding.keySet().removeIf(member -> nonbinding.contains(name + "#" + member)
                || ExtensionQualifiers.isNonbindingMember(name, member.toString()));
            @SuppressWarnings("unchecked")
            Qualifier<T> byValues = (Qualifier<T>) Qualifiers.byAnnotation(
                AnnotationMetadata.EMPTY_METADATA, new AnnotationValue<>(name, binding));
            resolved.add(byValues);
        }
        if (resolved.isEmpty()) {
            return null;
        }
        if (resolved.size() == 1) {
            return resolved.get(0);
        }
        @SuppressWarnings("unchecked")
        Qualifier<T>[] array = resolved.toArray(new Qualifier[0]);
        return Qualifiers.byQualifiers(array);
    }

    /**
     * The qualifiers of a bean, as the annotation instances the specification reports them as.
     *
     * <p>Every bean has {@code Any}, which the specification says rather than the bean declaring it, so it is
     * added here rather than being looked for.</p>
     *
     * @param annotationMetadata The metadata of the bean
     * @return The qualifiers
     */
    public static Set<Annotation> of(AnnotationMetadata annotationMetadata) {
        Set<Annotation> qualifiers = new LinkedHashSet<>();
        qualifiers.add(Any.Literal.INSTANCE);
        qualifiers.addAll(declared(annotationMetadata));
        return qualifiers;
    }

    /**
     * The qualifiers an element was annotated with, and only those.
     *
     * <p>It is what a bean's qualifiers are read from, and it is also what an observer method observes and what
     * an injected event or lookup is qualified by: in each of those the qualifiers are the ones that were
     * written, with nothing added. An observer that names none observes an event whatever it was fired with,
     * which is the rule of section 2.8.3, and adding {@code Any} to it the way a bean has it added would say
     * something else. {@code Any} itself is kept where it was written, because at an injection point it is the
     * difference between asking for every bean of the type and asking for the default one.</p>
     *
     * @param annotationMetadata The metadata of the element
     * @return The qualifiers
     */
    public static Set<Annotation> declared(AnnotationMetadata annotationMetadata) {
        return CdiQualifier.instances(CdiQualifier.declared(annotationMetadata));
    }
}
