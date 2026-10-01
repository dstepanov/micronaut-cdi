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
 * What the compiler knew of one class that the runtime would otherwise read from its generic signature: the
 * type variables the class declares, and every class and interface above it with the type arguments the
 * hierarchy gives each.
 *
 * <p>It is what an event of the class is matched by: an event is observed by the types of its class's
 * closure, and whether the class resolves its own type variables decides whether an object of it may be fired
 * at all (section 2.8.1).</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Internal
public @interface CdiTypeEntry {

    /**
     * The name of the class.
     *
     * @return The class name
     */
    String name();

    /**
     * The names of the type variables the class declares, in order.
     *
     * @return The variable names
     */
    String[] variables() default {};

    /**
     * The classes and interfaces above the class, each as the hierarchy parameterizes it; a variable of the
     * class among their arguments is recorded by its name.
     *
     * @return The supertypes
     */
    CdiRecordedType[] supertypes() default {};
}
