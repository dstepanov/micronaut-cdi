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

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Qualifier resolution for a producer and its disposed parameter (CDI 4.1 section 3.4.3). */
final class DisposerQualifiers {
    private static final String NAMED = "jakarta.inject.Named";
    private static final String ANY_MARKER = "io.micronaut.cdi.internal.metadata.CdiAny";

    private DisposerQualifiers() {
    }

    static boolean matches(Element producer, Element disposed, VisitorContext context) {
        List<AnnotationValue<?>> offered = qualifiers(producer, context);
        // Every bean has Any; a bean with only Named and/or Any also has Default.
        if (offered.stream().allMatch(q -> is(q, Cdi.ANY) || is(q, NAMED))) {
            offered.add(AnnotationValue.builder(Cdi.DEFAULT).build());
        }
        offered.add(AnnotationValue.builder(Cdi.ANY).build());
        List<AnnotationValue<?>> required = qualifiers(disposed, context);
        if (required.isEmpty()) {
            required.add(AnnotationValue.builder(Cdi.DEFAULT).build());
        }
        // Any does not cancel other required qualifiers. Extra qualifiers on the producer are permitted.
        return required.stream().allMatch(wanted -> offered.stream().anyMatch(wanted::matches));
    }

    private static boolean is(AnnotationValue<?> qualifier, String name) {
        return name.equals(qualifier.getAnnotationName());
    }

    private static List<AnnotationValue<?>> qualifiers(Element element, VisitorContext context) {
        AnnotationMetadata metadata = element.getAnnotationMetadata();
        List<AnnotationValue<?>> result = new ArrayList<>();
        for (String name : metadata.getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (name.startsWith("io.micronaut.context.annotation.")
                || name.startsWith("io.micronaut.core.annotation.")
                || name.startsWith("io.micronaut.cdi.internal.metadata.")) {
                continue;
            }
            // Use each declared occurrence; member metadata also carries the declaring class's qualifiers.
            for (AnnotationValue<?> value : metadata.getDeclaredAnnotationValuesByName(name)) {
                Map<CharSequence, Object> members = new LinkedHashMap<>(context.getAnnotationDefaultValues(name));
                Map<CharSequence, Object> defaults = value.getDefaultValues();
                if (defaults != null) {
                    members.putAll(defaults);
                }
                members.putAll(value.getValues());
                context.getClassElement(name).or(() -> context.getClassElement(name.replace('$', '.')))
                    .ifPresent(type -> type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                        .stream().filter(member -> member.hasAnnotation(Cdi.NONBINDING)
                            || member.hasAnnotation("io.micronaut.context.annotation.NonBinding"))
                        .forEach(member -> members.remove(member.getName())));
                // Micronaut records nonbinding member names inside the annotation value itself.
                for (String member : value.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)) {
                    members.remove(member);
                }
                members.remove(AnnotationUtil.NON_BINDING_ATTRIBUTE);
                result.add(new AnnotationValue<>(name, members, Map.of()));
            }
        }
        if (metadata.hasDeclaredAnnotation(ANY_MARKER)) {
            result.add(AnnotationValue.builder(Cdi.ANY).build());
        }
        return result;
    }
}
