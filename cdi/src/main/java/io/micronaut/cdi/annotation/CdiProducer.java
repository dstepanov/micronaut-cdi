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
 * Records, on a bean that was produced, the member that produced it.
 *
 * <p>Micronaut records that a bean came from a factory and which class the factory is, which is all it needs in
 * order to create the bean. The specification reports more than that: a program asking the container about a bean
 * is told whether it was produced by a method or by a field, and which one. That is known while the producer is
 * compiled and is recorded here, so that it can be reported without having to work it out again.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Internal
public @interface CdiProducer {

    /**
     * The class that declares the producer.
     *
     * @return The declaring class
     */
    Class<?> declaringType();

    /**
     * The name of the producer method or producer field.
     *
     * @return The member name
     */
    String member();

    /**
     * Whether the producer is a field rather than a method.
     *
     * @return Whether it is a field
     */
    boolean field() default false;
}
