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
package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A destruction that fails does not stop the ones after it: an {@code Error} a contextual throws, one exception
 * instance two contextuals throw, an exception that reaches Micronaut's scope, and an {@code Error} a shutdown
 * observer throws each leave every other instance destroyed.
 */
class ContextCleanupFailureTest {

    @Test
    void everyInstanceOfAnEndingRequestIsDestroyedWhateverOneThrows() {
        List<String> log = new CopyOnWriteArrayList<>();
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            RuntimeException shared = new IllegalStateException("thrown twice");
            requests.activate();
            try {
                Context requestContext = container.getContext(RequestScoped.class);
                for (Probe probe : List.of(
                    Probe.destroyedWith("error", log, () -> {
                        throw new AssertionError("a failed destruction");
                    }),
                    Probe.destroyedWith("shared-1", log, () -> {
                        throw shared;
                    }),
                    Probe.destroyedWith("shared-2", log, () -> {
                        throw shared;
                    }),
                    Probe.of("survivor", log))) {
                    requestContext.get(probe, container.createCreationalContext(probe));
                }
                container.createInstance().select(RequestTracked.class).get().touch();
            } finally {
                try {
                    requests.deactivate();
                } catch (RuntimeException | Error ignored) {
                    // what the destruction threw may be reported; what matters is what was destroyed
                }
            }
            assertTrue(log.containsAll(List.of("error", "shared-1", "shared-2", "survivor")), log.toString());
            assertTrue(RequestTracked.DESTROYED.getAndSet(false),
                "a bean of the request is destroyed whatever a contextual of the request threw");
        }
    }

    @Test
    void anErrorAShutdownObserverThrowsDoesNotStopTheShutdown() {
        ApplicationTracked.DESTROYED.set(false);
        ApplicationContext context = ApplicationContext.run();
        FailingShutdownObserver.ARMED.set(true);
        try {
            context.getBean(CdiBeanContainer.class).createInstance().select(ApplicationTracked.class).get().touch();
        } finally {
            try {
                context.close();
            } catch (RuntimeException | Error ignored) {
                // what the shutdown threw may be reported; what matters is what was destroyed
            }
        }
        assertTrue(ApplicationTracked.DESTROYED.get(),
            "the application scoped beans are destroyed though a Shutdown observer threw an Error");
    }

    /**
     * A bean of the request, which says it was destroyed.
     */
    @RequestScoped
    public static class RequestTracked {

        static final AtomicBoolean DESTROYED = new AtomicBoolean();

        void touch() {
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.set(true);
        }
    }

    /**
     * An application scoped bean, which says it was destroyed.
     */
    @ApplicationScoped
    public static class ApplicationTracked {

        static final AtomicBoolean DESTROYED = new AtomicBoolean();

        void touch() {
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.set(true);
        }
    }

    /**
     * Throws an {@code Error} as the container of the test that armed it shuts down.
     */
    @ApplicationScoped
    public static class FailingShutdownObserver {

        static final AtomicBoolean ARMED = new AtomicBoolean();

        void onShutdown(@Observes Shutdown shutdown) {
            if (ARMED.getAndSet(false)) {
                throw new AssertionError("a shutdown observer failed");
            }
        }
    }
}
