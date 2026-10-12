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

import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.Any;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The qualifiers of a bean in the two other shapes they are asked for in: the Micronaut qualifier a definition the
 * container registers is declared with, and the annotation instances the specification's interfaces report.
 *
 * <p>A lookup does not resolve by what is made here: it resolves by {@link CdiBeanQualifier}, which compares the
 * qualifiers of the specification by its own rules.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiQualifiers {

    private CdiQualifiers() {
    }

    /**
     * The Micronaut qualifier a definition the container registers for a synthetic bean is declared with, from the
     * values its qualifiers were recorded with while the application compiled. It describes the bean, the way
     * Micronaut keys a registration by it; a lookup of the bean resolves by {@link CdiBeanQualifier}.
     *
     * @param qualifiers The qualifiers, each as the values it was written with
     * @param nonbinding The members that take no part in resolution, each as {@code annotationName#memberName}
     * @param <T>        The bean type
     * @return The qualifier, or {@code null} when every bean of the type qualifies
     */
    public static <T> @Nullable Qualifier<T> declaredQualifierOf(List<? extends AnnotationValue<?>> qualifiers,
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
