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
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns an annotation into the values it was written with, and the values back into an annotation.
 *
 * <p>The specification is written in terms of annotation instances: a bean reports its qualifiers as a set of
 * them, and a program looks a bean up by handing some over. Micronaut records what an annotation was written with
 * as an {@link AnnotationValue} and never materializes the annotation itself. Both directions are needed, and
 * both are here.</p>
 *
 * <p>Reading an annotation a program hands over is done here. Making an annotation instance out of recorded
 * values is not: an instance is a reflection object, and {@link CdiReflection} makes it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAnnotations {

    private CdiAnnotations() {
    }

    /**
     * The values an annotation was written with that take part in binding, read off the annotation itself.
     *
     * <p>Micronaut reads every member in the form the compiled metadata stores it, so that a value read off a live
     * annotation compares equal to the same value read out of a definition. A member excluded from the comparison
     * of qualifiers is then left out: it takes no part in it from either side, and whatever it was given here, a
     * bean qualified the same way but for that member still qualifies. A nested annotation keeps every one of its
     * members, since what is not binding is a member of the qualifier and not of an annotation it carries.</p>
     *
     * @param annotation The annotation
     * @param <A>        The annotation type
     * @return The annotation value
     */
    public static <A extends Annotation> AnnotationValue<A> valueOf(A annotation) {
        AnnotationValue<A> read = AnnotationValue.of(annotation);
        Class<? extends Annotation> type = annotation.annotationType();
        Map<CharSequence, Object> values = null;
        for (Method member : type.getDeclaredMethods()) {
            if (isNonBinding(member)) {
                if (values == null) {
                    values = new LinkedHashMap<>(read.getValues());
                }
                values.remove(member.getName());
            }
        }
        return values == null ? read : new AnnotationValue<>(type.getName(), values);
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
     * container is asked when it compares two qualifiers or two interceptor bindings.
     *
     * <p>They are the same when they are of the same type and every member that takes part in the comparison is
     * equal. Which members those are was decided by whoever wrote the annotation, with {@code Nonbinding}, and
     * reading the values off the annotation leaves those out already.</p>
     *
     * @param one   The one annotation
     * @param other The other
     * @return Whether they are the same for the purposes of binding
     */
    public static boolean areEquivalent(Annotation one, Annotation other) {
        if (!one.annotationType().equals(other.annotationType())) {
            return false;
        }
        return valueOf(one).equals(valueOf(other));
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
            + AnnotationUtil.calculateHashCode(valueOf(annotation).getValues());
    }

    /**
     * Whether the member is excluded from the comparison of two annotations, which the specification says with
     * {@code jakarta.enterprise.util.Nonbinding}.
     */
    private static boolean isNonBinding(Method member) {
        if (ExtensionQualifiers.isNonbindingMember(member.getDeclaringClass().getName(), member.getName())) {
            // an extension said so during discovery, into the compiled metadata rather than onto the class
            return true;
        }
        for (Annotation annotation : member.getAnnotations()) {
            if ("jakarta.enterprise.util.Nonbinding".equals(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }
}
