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
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        if (qualifiers.length == 0) {
            // a lookup that names no qualifier is looking for the default one
            return Qualifiers.byAnnotation(Default.Literal.INSTANCE);
        }
        List<Qualifier<T>> resolved = new ArrayList<>(qualifiers.length);
        for (Annotation qualifier : qualifiers) {
            if (qualifier instanceof Any) {
                // every bean has the Any qualifier, so asking for it narrows nothing
                continue;
            }
            resolved.add(qualifierOf(qualifier));
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
     * The Micronaut qualifier that resolves the beans one qualifier of the specification does.
     *
     * <p>It is built from the values the annotation was written with rather than from the annotation itself,
     * because a qualifier built from the annotation is compared by its type alone: the member of a qualifier
     * takes part in the comparison, and section 2.4.2 has a bean qualified {@code @Chunky(true)} not resolving an
     * injection point that asks for {@code @Chunky(false)}.</p>
     */
    @SuppressWarnings("unchecked")
    private static <T> Qualifier<T> qualifierOf(Annotation qualifier) {
        if (qualifier instanceof Named named) {
            // a name is how Micronaut qualifies a bean of its own accord, and it has a qualifier for it
            return Qualifiers.byName(named.value());
        }
        // the qualifier the annotation names is a qualifier of the bean type rather than of the annotation type,
        // which is what the signature of the factory describes and what the cast reads it back as
        return (Qualifier<T>) Qualifiers.byAnnotation(
            AnnotationMetadata.EMPTY_METADATA, CdiAnnotations.valueOf(qualifier));
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
        Set<Annotation> qualifiers = new LinkedHashSet<>();
        if (annotationMetadata.hasAnnotation("io.micronaut.cdi.annotation.CdiAny")) {
            // Any was written here; it is carried as the marker so that Micronaut does not narrow by it
            qualifiers.add(Any.Literal.INSTANCE);
        }
        for (AnnotationValue<Annotation> annotation : AnnotationUtil.findQualifierAnnotations(annotationMetadata)) {
            // the list may carry a gap where the metadata records a qualifier it has no values for
            if (annotation == null) {
                continue;
            }
            String name = annotation.getAnnotationName();
            if (isMicronautOwn(name)) {
                continue;
            }
            if ("jakarta.inject.Named".equals(name)
                && annotationMetadata.hasAnnotation("io.micronaut.cdi.annotation.CdiName")) {
                // the name came through a stereotype — recorded as CdiName — so the bean has the name, but
                // Named is not among its qualifiers (section 2.6.1). The jakarta annotation beside it is the
                // default Micronaut materialized from the stereotype, not something the author wrote
                continue;
            }
            Annotation synthesized = synthesize(name, annotation);
            if (synthesized != null) {
                qualifiers.add(synthesized);
            }
        }
        return qualifiers;
    }

    /**
     * Whether the qualifier is one Micronaut declares on a bean of its own accord rather than one the author
     * wrote, and so is not a qualifier of the bean as far as the specification is concerned.
     */
    private static boolean isMicronautOwn(String name) {
        return "io.micronaut.context.annotation.Primary".equals(name)
            || "io.micronaut.context.annotation.Any".equals(name)
            || "io.micronaut.context.annotation.Type".equals(name);
    }

    /**
     * Materializes an annotation instance from what a bean was annotated with.
     *
     * <p>This is one of the two places the module produces an annotation by proxying it, and it is here because
     * the interfaces of the specification are written in terms of annotation instances: a bean reports its
     * qualifiers as a set of them. Nothing about resolving or injecting a bean goes through this.</p>
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Annotation synthesize(String name, AnnotationValue<Annotation> annotation) {
        try {
            Class<? extends Annotation> type = (Class<? extends Annotation>) Class.forName(
                name, false, CdiQualifiers.class.getClassLoader());
            return CdiAnnotations.annotationOf(type, annotation);
        } catch (ClassNotFoundException | LinkageError | IllegalArgumentException e) {
            return null;
        }
    }
}
