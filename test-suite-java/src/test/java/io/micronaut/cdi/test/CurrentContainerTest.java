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
package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The static entry point resolves to the container started last, and to the one started before it once that one
 * stops: the containers running are held by the provider the specification's API holds.
 */
class CurrentContainerTest {

    @Test
    void theEntryPointResolvesToTheContainerStartedLastAndFallsBack() {
        try (ApplicationContext first = ApplicationContext.run()) {
            CdiBeanContainer firstContainer = first.getBean(CdiBeanContainer.class);
            assertSame(firstContainer, CDI.current().getBeanContainer());
            try (ApplicationContext second = ApplicationContext.run()) {
                assertSame(second.getBean(CdiBeanContainer.class), CDI.current().getBeanContainer());
            }
            assertSame(firstContainer, CDI.current().getBeanContainer());
        }
    }
}
