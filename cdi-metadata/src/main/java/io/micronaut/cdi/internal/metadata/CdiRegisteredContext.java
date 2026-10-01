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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the bean definition generated for a context class a build compatible extension registered with
 * {@code MetaAnnotations.addContext} (section 2.10.1), and records the scope the context serves: the container
 * reads the scope off the definition and instantiates the context through it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Internal
public @interface CdiRegisteredContext {

    /**
     * The scope annotation the context holds the instances of.
     *
     * @return The scope annotation
     */
    Class<?> scope();

    /**
     * Whether the scope is a normal one.
     *
     * @return Whether it is normal
     */
    boolean normal() default false;
}
