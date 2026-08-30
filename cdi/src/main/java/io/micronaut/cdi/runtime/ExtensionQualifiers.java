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

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The annotations a build compatible extension made qualifiers of (section 2.10.1): the annotation class
 * itself does not say it is a qualifier — the extension said so during discovery, into the compiled metadata —
 * so the runtime checks that read the class reflectively look here as well.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ExtensionQualifiers {

    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();
    private static final Set<String> NONBINDING_MEMBERS = ConcurrentHashMap.newKeySet();

    private ExtensionQualifiers() {
    }

    /**
     * Whether an extension excluded the member from the comparison of the annotation, the way
     * {@code jakarta.enterprise.util.Nonbinding} does.
     *
     * @param annotationName The annotation's name
     * @param memberName     The member's name
     * @return Whether the member is excluded
     */
    public static boolean isNonbindingMember(String annotationName, String memberName) {
        return NONBINDING_MEMBERS.contains(annotationName + "#" + memberName);
    }

    /**
     * Remembers a member an extension excluded from comparison.
     *
     * @param annotationName The annotation's name
     * @param memberName     The member's name
     */
    public static void registerNonbindingMember(String annotationName, String memberName) {
        NONBINDING_MEMBERS.add(annotationName + "#" + memberName);
    }

    /**
     * Whether the given annotation is a qualifier: it says so itself, or an extension said so.
     *
     * @param type The annotation type
     * @return Whether it is a qualifier
     */
    public static boolean isQualifier(Class<? extends Annotation> type) {
        return type.isAnnotationPresent(jakarta.inject.Qualifier.class) || NAMES.contains(type.getName());
    }

    /**
     * Remembers an annotation an extension made a qualifier of.
     *
     * @param name The annotation's name
     */
    public static void register(String name) {
        NAMES.add(name);
    }
}
