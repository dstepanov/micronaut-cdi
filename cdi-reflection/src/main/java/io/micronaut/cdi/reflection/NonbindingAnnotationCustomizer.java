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
package io.micronaut.cdi.reflection;

import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.reflection.ReflectionAnnotationCustomizer;
import jakarta.enterprise.util.Nonbinding;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists the members the specification marks {@code jakarta.enterprise.util.Nonbinding} under {@code $nonBinding}
 * on every annotation Micronaut reads reflectively, beside the ones it lists for its own {@code NonBinding}: the
 * runtime counterpart of the mapper that reads the specification's mark while an application compiles, so that a
 * qualifier or an interceptor binding read from an instance is recorded as the compiled one is.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NonbindingAnnotationCustomizer implements ReflectionAnnotationCustomizer {

    @Override
    public void customize(Annotation annotation, Map<CharSequence, Object> values) {
        // read off the members as each annotation is read, which is once per instance read reflectively: no
        // record of them is kept beside the one the JVM keeps of the members of a class
        List<String> marked = markedOf(annotation.annotationType());
        if (marked.isEmpty()) {
            return;
        }
        Set<String> listed = new LinkedHashSet<>();
        Object micronauts = values.get(AnnotationUtil.NON_BINDING_ATTRIBUTE);
        if (micronauts instanceof String[] names) {
            listed.addAll(List.of(names));
        }
        listed.addAll(marked);
        // the attribute lists itself, as the processor records it
        listed.add(AnnotationUtil.NON_BINDING_ATTRIBUTE);
        values.put(AnnotationUtil.NON_BINDING_ATTRIBUTE, listed.toArray(String[]::new));
    }

    private static List<String> markedOf(Class<? extends Annotation> type) {
        List<String> marked = new ArrayList<>(1);
        for (Method member : type.getDeclaredMethods()) {
            if (member.isAnnotationPresent(Nonbinding.class)) {
                marked.add(member.getName());
            }
        }
        return marked;
    }
}
