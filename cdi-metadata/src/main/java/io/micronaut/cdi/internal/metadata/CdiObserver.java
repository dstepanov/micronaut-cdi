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
 * <p>What is recorded is what the executable method Micronaut generated cannot say: which parameter is observed,
 * the observed type as it was written - with the wildcards and type variables the compiled argument erases - and
 * what the observer annotation and the observer's priority say. The qualifiers of the observed parameter are read
 * off the parameter's compiled metadata.</p>
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
     * The name of the member {@link #observedParameter()}.
     */
    String OBSERVED_PARAMETER = "observedParameter";

    /**
     * The name of the member {@link #observedType()}.
     */
    String OBSERVED_TYPE = "observedType";

    /**
     * The name of the member {@link #async()}.
     */
    String ASYNC = "async";

    /**
     * The name of the member {@link #ifExists()}.
     */
    String IF_EXISTS = "ifExists";

    /**
     * The name of the member {@link #staticMethod()}.
     */
    String STATIC_METHOD = "staticMethod";

    /**
     * The name of the member {@link #during()}.
     */
    String DURING = "during";

    /**
     * The name of the member {@link #priority()}.
     */
    String PRIORITY = "priority";

    /**
     * The position of the parameter the method observes, since an observer method may take injected parameters
     * before and after it.
     *
     * @return The zero based position of the observed parameter
     */
    int observedParameter();

    /**
     * The observed event type as it was written, with the wildcards and type variables the compiled argument
     * erases: one record, or none where the observer was compiled before the type was recorded.
     *
     * @return The observed type
     */
    CdiRecordedType[] observedType() default {};

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
     * <p>It is recorded here rather than read from Micronaut's {@code Order}: the specification puts the
     * priority of an observer on its event parameter, which Micronaut maps to nothing, while the {@code Order}
     * Micronaut maps a {@code Priority} to is the order of a bean - the declaring bean's own, which is not the
     * observer's. A synthetic observer records its priority the same way, for the same reason.</p>
     *
     * @return The priority
     */
    int priority() default 2500;
}
