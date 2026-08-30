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

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records, on a method, that it is an observer method, and what about it the container needs in order to notify
 * it.
 *
 * <p>What the method observes is not recorded here. The parameter it observes carries its own type and its own
 * qualifiers, and Micronaut has already compiled both of those into the executable method it generated: reading
 * them off the parameter at runtime is reading what the author wrote, and recording them again would be a second
 * copy to keep in step with the first. What is recorded is only what cannot be read off the parameter — which
 * parameter it is, and the three things the observer annotation itself says.</p>
 *
 * <p>The method is executable, so that notifying an observer is a direct invocation of the executable method
 * Micronaut generated for it rather than a reflective one.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Executable
@Internal
public @interface CdiObserver {

    /**
     * The position of the parameter the method observes, since an observer method may take injected parameters
     * before and after it.
     *
     * @return The zero based position of the observed parameter
     */
    int observedParameter();

    /**
     * Whether the method observes an event fired asynchronously, which is what
     * {@code jakarta.enterprise.event.ObservesAsync} declares.
     *
     * @return Whether the observer is asynchronous
     */
    boolean async() default false;

    /**
     * Whether the method is notified only when an instance of its bean already exists in the current context,
     * which is what {@code jakarta.enterprise.event.Reception#IF_EXISTS} asks for.
     *
     * @return Whether the observer is notified only if its bean exists
     */
    boolean ifExists() default false;

    /**
     * Whether the method is static, which the specification allows and which is notified without an instance of
     * the bean that declares it.
     *
     * @return Whether the observer method is static
     */
    boolean staticMethod() default false;

    /**
     * The transaction phase the observer asked to be notified in, as the specification's enum names it.
     *
     * @return The phase
     */
    String during() default "IN_PROGRESS";

    /**
     * The priority the observer is notified in, which the specification reads from
     * {@code jakarta.annotation.Priority} and defaults to the middle of the range.
     *
     * @return The priority
     */
    int priority() default 2500;
}
