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
 * Records, on a producer method or a producer field, the disposer method that disposes of what it produced.
 *
 * <p>The specification has the disposer of a produced bean declared beside the producer, on the same class, and
 * matched to it by the type and the qualifiers of its {@code jakarta.enterprise.inject.Disposes} parameter. That
 * matching is done once, while the producer is compiled, and the method it resolved to is named here; at runtime
 * the disposer is the executable method Micronaut generated for it, so disposing of a bean never reflects.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Internal
public @interface CdiDisposer {

    /**
     * The class that declares both the producer and its disposer.
     *
     * @return The declaring class
     */
    Class<?> declaringType();

    /**
     * The name of the disposer method.
     *
     * @return The method name
     */
    String method();

    /**
     * The position of the {@code jakarta.enterprise.inject.Disposes} parameter, since the disposer may take
     * injected parameters before and after it.
     *
     * @return The zero based position of the disposed parameter
     */
    int disposedParameter();

    /**
     * Whether the disposer method is static, and so is dispatched reflectively: Micronaut writes no executable
     * method for a static method.
     *
     * @return Whether the disposer is static
     */
    boolean staticMethod() default false;

    /**
     * Whether the disposer is public, which decides whether a client proxy of the declaring bean delegates the
     * call. Recorded while the disposer is compiled, so that deciding it needs no reflection.
     *
     * @return Whether it is public
     */
    boolean publicMethod() default false;
}
