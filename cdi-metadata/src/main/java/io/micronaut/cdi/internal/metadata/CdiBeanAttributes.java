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
 * The attributes of a bean that CDI 5.0 added and that belong to the bean itself rather than to the class it is
 * declared by: whether it is a reserve ({@code Reserve}), eagerly initialized ({@code Eager}) and auto-closeable
 * ({@code AutoClose}), each written on it or carried by a stereotype it declares.
 *
 * <p>The processor writes the record on a class bean that has any of them, and on every producer of a class that
 * has the record or that has any of them itself, with all three members written: the annotation metadata of a
 * producer carries what the class that declares it carries behind its own, and none of the three is inherited by a
 * producer from its class (sections 2.8.1, 2.9, 2.10).</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Internal
public @interface CdiBeanAttributes {

    /**
     * Whether the bean is a reserve, which a priority selects: non-reserve beans take precedence over it.
     *
     * @return Whether it is a reserve
     */
    boolean reserve() default false;

    /**
     * Whether the bean is eagerly initialized: its contextual instance is created before {@code Startup} is fired.
     *
     * @return Whether it is eager
     */
    boolean eager() default false;

    /**
     * Whether the bean is auto-closeable: an instance whose class implements {@code AutoCloseable} is closed as it
     * is destroyed.
     *
     * @return Whether it is auto-closeable
     */
    boolean autoClose() default false;
}
