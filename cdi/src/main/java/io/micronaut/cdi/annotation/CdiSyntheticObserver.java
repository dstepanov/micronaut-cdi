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
 * One synthetic observer a build compatible extension described in its synthesis phase (section 2.10.5),
 * recorded while the application compiled on the bean definition generated for the observer class.
 *
 * <p>Two members are not declared here, because the language cannot declare them: {@code qualifiers} holds the
 * observed qualifiers, each of its own annotation type, and {@code eventType} holds the observed type as a
 * {@link CdiRecordedType}, whose type arguments nest.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Internal
public @interface CdiSyntheticObserver {

    /**
     * The name of the undeclared member that holds the observed qualifiers.
     */
    String QUALIFIERS = "qualifiers";

    /**
     * The name of the member that holds the observed type.
     */
    String EVENT_TYPE = "eventType";

    /**
     * What tells this observer from the others the compilation recorded.
     *
     * @return The identifier
     */
    String id();

    /**
     * The annotation types of the observed qualifiers, in the order of the qualifiers.
     *
     * @return The annotation types
     */
    Class<?>[] qualifierTypes() default {};

    /**
     * The members of the observed qualifiers that take no part in resolution, each as
     * {@code annotationName#memberName}.
     *
     * @return The non-binding members
     */
    String[] nonbinding() default {};

    /**
     * The order of the observer among the observers of an event.
     *
     * @return The priority
     */
    int priority();

    /**
     * Whether the observer is asynchronous.
     *
     * @return Whether it is asynchronous
     */
    boolean async() default false;

    /**
     * The name of the transaction phase the observer is notified in.
     *
     * @return The phase
     */
    String transactionPhase() default "IN_PROGRESS";

    /**
     * What the extension attached for the observer to read.
     *
     * @return The parameters
     */
    CdiSyntheticParameter[] params() default {};
}
