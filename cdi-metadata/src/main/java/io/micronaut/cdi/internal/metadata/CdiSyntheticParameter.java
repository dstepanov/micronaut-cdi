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
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One parameter an extension attached to a synthetic bean or observer with {@code withParam} (section 2.10.5).
 *
 * <p>The specification allows a parameter to be a boolean, an int, a long, a double, a string, an enum constant,
 * a class, an annotation or an invoker, or an array of one of those, and every one of them is something an
 * annotation value holds. The member named by {@link #kind()} holds the value, as one element for a single
 * value and as the elements of the array otherwise.</p>
 *
 * <p>An annotation parameter is of its own annotation type, which the language cannot declare a member for: it
 * is held by an undeclared member, {@code annotations}, as nested annotation values.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Internal
public @interface CdiSyntheticParameter {

    /**
     * The name of the undeclared member that holds annotation values.
     */
    String ANNOTATIONS = "annotations";

    /**
     * The key of the parameter.
     *
     * @return The key
     */
    String name();

    /**
     * What the parameter is: {@code BOOLEAN}, {@code INT}, {@code LONG}, {@code DOUBLE}, {@code STRING},
     * {@code ENUM}, {@code CLASS}, {@code ANNOTATION} or {@code INVOKER}.
     *
     * @return The kind
     */
    String kind();

    /**
     * Whether the parameter is an array.
     *
     * @return Whether it is an array
     */
    boolean array() default false;

    /**
     * The boolean values.
     *
     * @return The values
     */
    boolean[] booleans() default {};

    /**
     * The int values.
     *
     * @return The values
     */
    int[] ints() default {};

    /**
     * The long values.
     *
     * @return The values
     */
    long[] longs() default {};

    /**
     * The double values.
     *
     * @return The values
     */
    double[] doubles() default {};

    /**
     * The string values, and the names of the enum constants.
     *
     * @return The values
     */
    String[] strings() default {};

    /**
     * The class values.
     *
     * @return The values
     */
    Class<?>[] classes() default {};

    /**
     * The enum the constants named in {@link #strings()} belong to, or the annotation type of the values of an
     * annotation parameter.
     *
     * @return The type
     */
    Class<?> type() default void.class;

    /**
     * The invokers.
     *
     * @return The values
     */
    CdiRecordedInvoker[] invokers() default {};
}
