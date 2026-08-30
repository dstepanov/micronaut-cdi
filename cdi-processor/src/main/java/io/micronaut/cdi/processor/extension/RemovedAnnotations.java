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
package io.micronaut.cdi.processor.extension;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.ParameterElement;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the build compatible extensions took off which element, shared across the compilation's visitors: a
 * removal made through one visitor's view of a parameter does not reach another visitor's view of the same
 * parameter, so the removals are remembered here, by what names the element rather than by the element object.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RemovedAnnotations {

    private static final Map<String, Set<String>> REMOVED = new ConcurrentHashMap<>();

    private RemovedAnnotations() {
    }

    static void record(Element element, String annotation) {
        REMOVED.computeIfAbsent(keyOf(element), key -> ConcurrentHashMap.newKeySet()).add(annotation);
    }

    /**
     * Whether an extension took the annotation off the element.
     *
     * @param element    The element
     * @param annotation The annotation name
     * @return Whether it was removed
     */
    public static boolean isRemoved(Element element, String annotation) {
        Set<String> names = REMOVED.get(keyOf(element));
        return names != null && names.contains(annotation);
    }

    /**
     * Forgets everything: what one deployment's extensions removed says nothing about the next deployment.
     */
    public static void reset() {
        REMOVED.clear();
    }

    private static String keyOf(Element element) {
        if (element instanceof ParameterElement parameter) {
            return keyOf(parameter.getMethodElement()) + ":" + parameter.getName();
        }
        if (element instanceof io.micronaut.inject.ast.MethodElement method) {
            // the signature is part of the key: a removal from one overload's parameter says nothing about
            // the other overload's
            StringBuilder key = new StringBuilder(method.getDeclaringType().getName())
                .append('#').append(method.getName()).append('(');
            for (ParameterElement parameter : method.getParameters()) {
                key.append(parameter.getType().getName()).append(',');
            }
            return key.append(')').toString();
        }
        if (element instanceof MemberElement member) {
            return member.getDeclaringType().getName() + "#" + member.getName();
        }
        return element.getName();
    }
}
