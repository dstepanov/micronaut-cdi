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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.context.ApplicationContext;
import org.jspecify.annotations.Nullable;

/**
 * The container of the deployment under test.
 *
 * <p>The kit deploys one archive per test class and asserts against the container that came up for it. The
 * adapter starts that container, and everything else — the enricher injecting the test instance, the SPI
 * implementations the kit's assertions reach through — needs to find it. It is one at a time, which is how the
 * kit runs its suite.</p>
 */
public final class CurrentDeployment {

    private static volatile @Nullable ApplicationContext context;

    private CurrentDeployment() {
    }

    static void started(ApplicationContext started) {
        context = started;
    }

    static void stopped() {
        context = null;
    }

    /**
     * The container of the deployment under test.
     *
     * @return The container
     */
    public static ApplicationContext context() {
        ApplicationContext current = context;
        if (current == null) {
            throw new IllegalStateException("No deployment is under test");
        }
        return current;
    }
}
