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
package io.micronaut.cdi.annotation;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A type an extension composed in the language model, recorded so that the container can hand it out as a
 * {@code java.lang.reflect.Type} without reading anything back: the class, how many array dimensions it has,
 * and its type arguments.
 *
 * <p>The type arguments are held by an undeclared member, {@code arguments}, as nested values of this same
 * annotation: an annotation interface cannot declare a member of its own type.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Internal
public @interface CdiRecordedType {

    /**
     * The name of the undeclared member that holds the type arguments.
     */
    String ARGUMENTS = "arguments";

    /**
     * The class, or the component class of an array.
     *
     * @return The class
     */
    Class<?> value();

    /**
     * The dimensions of an array of {@link #value()}, zero for the class itself.
     *
     * @return The dimensions
     */
    int dimensions() default 0;
}
