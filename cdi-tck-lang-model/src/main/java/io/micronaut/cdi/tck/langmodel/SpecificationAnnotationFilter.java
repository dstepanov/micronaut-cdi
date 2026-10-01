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
package io.micronaut.cdi.tck.langmodel;

import io.micronaut.cdi.lang.model.ast.LanguageModelAnnotationFilter;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.inject.ast.ClassElement;

/**
 * Narrows what the language model reports to what the specification's model would report of the source, which
 * is what the kit asserts on: none of what Micronaut writes into its own record.
 *
 * <p>Left out: every annotation of Micronaut's annotation packages, which is where its mappers and remappers
 * put what they add; and, on a use of a type in a null-marked class or package, whatever carries Micronaut's
 * non-null stereotype, which is the marker Micronaut writes on every such type that is not nullable. A deployment
 * that wants to see Micronaut's annotations registers no such filter.</p>
 */
public final class SpecificationAnnotationFilter implements LanguageModelAnnotationFilter {

    private static final String[] MICRONAUT_PACKAGES = {
        "io.micronaut.core.annotation.", "io.micronaut.context.annotation.", "io.micronaut.inject.annotation.",
        "io.micronaut.aop.", "io.micronaut.runtime.", "io.micronaut.cdi.annotation.", "io.micronaut.cdi.processor.",
    };

    // jspecify's marker as written, and as Micronaut remaps it
    private static final String[] NULL_MARKED = {
        "org.jspecify.annotations.NullMarked", "io.micronaut.core.annotation.NullMarked",
    };

    @Override
    public boolean isReported(String annotationName, Place place) {
        for (String prefix : MICRONAUT_PACKAGES) {
            if (annotationName.startsWith(prefix)) {
                return false;
            }
        }
        if (place.onType() && place.declaringType() != null && isNullMarked(place.declaringType())) {
            // the synthesised marker arrives remapped, under Micronaut's non-null stereotype
            return !AnnotationUtil.NON_NULL.equals(annotationName)
                && !"org.jspecify.annotations.NonNull".equals(annotationName);
        }
        return true;
    }

    private static boolean isNullMarked(ClassElement declaring) {
        for (String name : NULL_MARKED) {
            if (declaring.hasStereotype(name) || declaring.getPackage().hasStereotype(name)) {
                return true;
            }
        }
        return false;
    }
}
