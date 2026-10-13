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
 * Records the declaration of an injected method: the class that declares it and the erasure of each parameter
 * type it declares.
 *
 * <p>The definition of a bean compiles a method its class inherits from a generic class with the type variables
 * bound to what the class gives them, so a parameter of {@code T} is compiled as the type bound to it. Those types
 * may fit another overload of the name as well, even a more specific one, and nothing in them says which method
 * was injected. The reflection module finds the member of the injection point by this record exactly.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Internal
public @interface CdiInjectedMethod {

    /**
     * The class that declares the method.
     *
     * @return The class
     */
    Class<?> declaringType();

    /**
     * The erasure of each parameter type the method declares: the binary name of its class, followed by a pair of
     * brackets for each dimension of an array.
     *
     * @return The names
     */
    String[] parameterTypes() default {};
}
