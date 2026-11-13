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
 * An injection point a build compatible extension registered for a synthetic bean with
 * {@code SyntheticBeanBuilder.withInjectionPoint} (CDI 5.0): the type and the qualifiers its creation and
 * destruction functions may look up through {@code SyntheticInjections}, validated as the container starts.
 *
 * <p>As on {@link CdiSyntheticBean}, the qualifiers are held by an undeclared member, {@code qualifiers}, as nested
 * annotation values; none stands for {@code @Default}.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Internal
public @interface CdiSyntheticInjectionPoint {

    /**
     * The name of the undeclared member that holds the qualifiers.
     */
    String QUALIFIERS = "qualifiers";

    /**
     * The required type.
     *
     * @return The type
     */
    CdiRecordedType type();

    /**
     * The annotation types of the qualifiers, in the order of the qualifiers.
     *
     * @return The annotation types
     */
    Class<?>[] qualifierTypes() default {};
}
