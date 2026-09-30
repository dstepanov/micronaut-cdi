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

import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;

/**
 * The places an annotation instance crosses the boundary between the specification's interfaces, which speak
 * of instances, and Micronaut, which records an annotation as the values it was written with.
 *
 * <p>Nothing is read or made here. An instance is made, and the members of one a program hands over are read,
 * by {@link CdiReflection}; two annotations are compared as the {@link CdiQualifier} each is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAnnotations {

    private CdiAnnotations() {
    }

    /**
     * The annotation the given values describe.
     *
     * <p>An annotation instance is a reflection object: it is made by the module that answers the reflective
     * parts of the specification's API, and asking for one without that module fails with a message that names
     * it.</p>
     *
     * @param type  The annotation type
     * @param value The values it was written with, if any were recorded
     * @param <A>   The annotation type
     * @return The annotation
     * @throws UnsupportedOperationException Where the reflection module is not on the classpath
     */
    public static <A extends Annotation> A annotationOf(Class<A> type, @Nullable AnnotationValue<?> value) {
        return CdiReflection.current("An instance of the annotation " + type.getName()).annotation(type, value);
    }

    /**
     * Whether two annotations are the same as far as binding one thing to another goes, which is what the
     * container is asked when it compares two qualifiers or two interceptor bindings: they are of the same
     * type, and every member that takes part in the comparison is equal.
     *
     * @param one   The one annotation
     * @param other The other
     * @return Whether they are the same for the purposes of binding
     */
    public static boolean areEquivalent(Annotation one, Annotation other) {
        if (!one.annotationType().equals(other.annotationType())) {
            return false;
        }
        return CdiQualifier.ofInstance(one).matches(CdiQualifier.ofInstance(other));
    }

    /**
     * The hash code of an annotation over the members that take part in the comparison, so that two annotations
     * the container calls the same hash the same.
     *
     * @param annotation The annotation
     * @return The hash code
     */
    public static int bindingHashCode(Annotation annotation) {
        return annotation.annotationType().getName().hashCode()
            + AnnotationUtil.calculateHashCode(CdiQualifier.ofInstance(annotation).binding().getValues());
    }
}
