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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;

/**
 * A check a bootstrap hands the container, run as the container starts: after its beans are defined and before
 * the deployment is validated and the application is told it started, so that a deployment the check refuses has
 * no {@code Startup} observed.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public interface StartCheck {

    /**
     * Checks the container that is starting.
     *
     * @param beanContext The context of the container
     */
    void check(BeanContext beanContext);
}
