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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records the type variables among an injection point's type arguments, which the compiled argument erases to
 * their first bound: resolution by the rules of section 2.4.2.1 needs every bound of the variable, and this is
 * where the compiler leaves them for the runtime to read.
 *
 * <p>Each entry is {@code position=bound,bound,...}: the position of the type argument that was a variable,
 * and the names of its bounds in order.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Internal
public @interface CdiGenericVariables {

    /**
     * The recorded variables, one entry per type argument that was one.
     *
     * @return The entries
     */
    String[] value();
}
