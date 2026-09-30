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
 * An invoker an extension built in its registration phase (CDI 4.1, chapter 7) and attached to a synthetic
 * component as a parameter: what names the method, and what the builder asked to be looked up.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Internal
public @interface CdiRecordedInvoker {

    /**
     * The name of the bean class.
     *
     * @return The class name
     */
    String beanClass();

    /**
     * The name of the method.
     *
     * @return The method name
     */
    String method();

    /**
     * The names of the method's parameter types.
     *
     * @return The type names
     */
    String[] parameterTypes() default {};

    /**
     * Whether the method is static.
     *
     * @return Whether it is static
     */
    boolean staticMethod() default false;

    /**
     * Whether the instance is looked up.
     *
     * @return Whether it is looked up
     */
    boolean instanceLookup() default false;

    /**
     * Which arguments are looked up, by position.
     *
     * @return One flag per parameter
     */
    boolean[] argumentLookups() default {};
}
