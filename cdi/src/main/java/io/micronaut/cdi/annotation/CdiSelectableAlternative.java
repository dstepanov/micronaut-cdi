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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An alternative that no priority selected, and what would select it at runtime instead.
 *
 * <p>Section 2.1.7 leaves an alternative without a priority out of the beans, but the SE bootstrap of the
 * specification lets a program select one as the container is built — {@code selectAlternatives} names the
 * class, {@code selectAlternativeStereotypes} a stereotype it carries. The compiler writes this beside the
 * {@link UnselectedAlternative} condition so that the condition knows which names select the bean.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
@Retention(RetentionPolicy.RUNTIME)
public @interface CdiSelectableAlternative {

    /**
     * The class whose selection enables the bean.
     *
     * @return The class name
     */
    String value();

    /**
     * The alternative stereotypes the bean carries, any of whose selection enables it.
     *
     * @return The stereotype annotation names
     */
    String[] stereotypes() default {};

    /**
     * Whether the alternative is the producer member itself rather than the class: a produced bean carries the
     * annotations of the class it produces, and only the member's own selection is meant by it.
     *
     * @return Whether the member is the alternative
     */
    boolean producer() default false;
}
