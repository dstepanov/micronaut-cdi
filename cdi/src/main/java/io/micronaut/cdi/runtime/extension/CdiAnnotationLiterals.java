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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.cdi.runtime.CdiAnnotations;
import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Annotation;

/**
 * Materializes an annotation from its type alone, for the places the specification lets an extension name a
 * qualifier by its class rather than hand one over.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAnnotationLiterals {

    private CdiAnnotationLiterals() {
    }

    /**
     * The annotation of the given type, with every member left at its default.
     *
     * @param type The annotation type
     * @return The annotation
     */
    public static Annotation of(Class<? extends Annotation> type) {
        return CdiAnnotations.annotationOf(type, null);
    }
}
