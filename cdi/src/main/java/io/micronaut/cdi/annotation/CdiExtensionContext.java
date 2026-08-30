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
 * Records, on a scope annotation a build compatible extension registered, which context classes hold the
 * scope's instances (section 2.10.1): the runtime reads it off the beans of the scope and stands the contexts
 * up as it starts.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
@Internal
public @interface CdiExtensionContext {

    /**
     * The names of the context classes.
     *
     * @return The names
     */
    String[] value();

    /**
     * The name of the scope annotation itself.
     *
     * @return The name
     */
    String scopeAnnotation() default "";

    /**
     * Whether the scope is a normal one.
     *
     * @return Whether it is normal
     */
    boolean normal() default false;
}
