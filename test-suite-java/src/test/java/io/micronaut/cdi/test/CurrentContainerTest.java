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
import io.micronaut.cdi.internal.runtime.MicronautCDIProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The static entry point resolves to the container started last, and to the one started before it once that one
 * stops: the containers running are held by the provider the specification's API holds. Once none runs, it reaches
 * the provider of another implementation, and a container whose context failed to start is never the one it
 * resolves to.
 */
class CurrentContainerTest {

    private static final String FAIL_START = "current-container-test.fail-start";

    @AfterEach
    void stopTheOtherImplementation() {
        OtherCdiProvider.answering = false;
    }

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

    @Test
    void theEntryPointReachesAnotherImplementationOnceNoContainerRuns() {
        OtherCdiProvider.answering = true;
        try (ApplicationContext context = ApplicationContext.run()) {
            // a container that runs is the current one, whatever else could answer
            assertSame(context.getBean(CdiBeanContainer.class), CDI.current().getBeanContainer());
        }
        assertSame(OtherCdiProvider.CURRENT, CDI.current());
        OtherCdiProvider.answering = false;
        assertThrows(IllegalStateException.class, CDI::current);
    }

    @Test
    void aProviderAProgramSetIsLeftInPlace() {
        CDI.setCDIProvider(new OtherCdiProvider());
        OtherCdiProvider.answering = true;
        try (ApplicationContext ignored = ApplicationContext.run()) {
            assertSame(OtherCdiProvider.CURRENT, CDI.current());
        } finally {
            // the specification's API cannot be told to forget a provider: this module's is set in its place again
            CDI.setCDIProvider(new MicronautCDIProvider());
        }
    }

    @Test
    void aContainerWhoseContextFailedToStartIsNotTheCurrentOne() {
        try (ApplicationContext first = ApplicationContext.run()) {
            CdiBeanContainer firstContainer = first.getBean(CdiBeanContainer.class);
            assertThrows(IllegalStateException.class, () -> ApplicationContext.run(Map.of(FAIL_START, true)));
            assertSame(firstContainer, CDI.current().getBeanContainer());
        }
    }

    /**
     * Fails the start of the context it is enabled in, once the container of that context has started.
     */
    @Singleton
    @Requires(property = FAIL_START, value = "true")
    public static class FailingStart implements ApplicationEventListener<StartupEvent> {
        @Override
        public void onApplicationEvent(StartupEvent event) {
            throw new IllegalStateException("the context cannot start");
        }
    }
}
