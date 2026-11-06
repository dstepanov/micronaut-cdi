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
package io.micronaut.cdi.internal.context;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.inject.BeanIdentifier;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Work the request's propagated context was handed to may find the request live, and be held up before it creates a
 * bean of the request while the request ends: Micronaut's scope has handed it the request's bean map and has not yet
 * announced the creation. The bean it then creates is destroyed at once and the creation fails as one in an inactive
 * context, rather than being put where nothing destroys it, neither the request nor the container's shutdown.
 *
 * <p>The window is held open deterministically: the worker reaches it and waits on the request's creation lock of
 * the bean, which the test holds while the request ends.</p>
 */
class RequestRetirementRaceTest {

    /**
     * Counts the baskets, held by the container itself rather than behind a client proxy, so that it is read after
     * the container is shut down.
     */
    @Singleton
    public static class Tally {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger destroyed = new AtomicInteger();

        public void created() {
            created.incrementAndGet();
        }

        public void destroyed() {
            destroyed.incrementAndGet();
        }

        public int createdCount() {
            return created.get();
        }

        public int destroyedCount() {
            return destroyed.get();
        }
    }

    @RequestScoped
    public static class Basket {
        @Inject
        Tally tally;

        @PostConstruct
        public void created() {
            tally.created();
        }

        @PreDestroy
        public void destroyed() {
            tally.destroyed();
        }

        public String id() {
            return "basket";
        }
    }

    @Test
    void aBeanCreatedForARequestThatEndedBeforeItsCreationWasAnnouncedIsDestroyed() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            ApplicationContext context = ApplicationContext.run();
            Tally tally;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                RequestScope scope = context.getBean(RequestScope.class);
                Basket basket = context.getBean(Basket.class);
                tally = context.getBean(Tally.class);

                // the identifier Micronaut keeps the bean under, the same in every request
                AtomicReference<BeanIdentifier> identifier = new AtomicReference<>();
                scope.run(() -> {
                    assertEquals("basket", basket.id());
                    identifier.set(identifierOf(scope.getScopeMap(false), Basket.class));
                });
                assertNotNull(identifier.get());
                assertEquals(tally.createdCount(), tally.destroyedCount());
                int createdBefore = tally.createdCount();

                assertTrue(scope.activate());
                PropagatedContext captured = PropagatedContext.get();
                Object lock = scope.getCreationLock(scope.getScopeMap(false), identifier.get());
                Thread worker = new Thread(() -> captured.propagate(() -> {
                    try {
                        basket.id();
                    } catch (RuntimeException e) {
                        failure.set(e);
                    }
                }), "request-worker");
                synchronized (lock) {
                    worker.start();
                    awaitBlockedOnCreation(worker);
                    // the worker holds the request's bean map and has announced no creation: the request ends
                    scope.deactivate();
                    assertFalse(scope.isActive());
                }
                worker.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(worker.isAlive());
                assertEquals(createdBefore + 1, tally.createdCount(), "the worker created the bean");
                assertEquals(tally.createdCount(), tally.destroyedCount(),
                    "the bean created for the ended request is destroyed");
                assertTrue(causedBy(failure.get(), ContextNotActiveException.class),
                    () -> "the creation fails as one in an inactive context: " + failure.get());
            } finally {
                context.close();
            }
            assertEquals(tally.createdCount(), tally.destroyedCount(), "nothing is left for the shutdown either");
        });
    }

    private static BeanIdentifier identifierOf(Map<BeanIdentifier, CreatedBean<?>> beans, Class<?> type) {
        for (Map.Entry<BeanIdentifier, CreatedBean<?>> entry : beans.entrySet()) {
            if (entry.getValue().definition().getBeanType() == type) {
                return entry.getKey();
            }
        }
        return fail("no bean of " + type + " in " + beans.keySet());
    }

    private static void awaitBlockedOnCreation(Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (worker.getState() == Thread.State.BLOCKED && Arrays.stream(worker.getStackTrace())
                .anyMatch(frame -> frame.getMethodName().equals("getOrCreateLockingPerBean"))) {
                return;
            }
            Thread.sleep(5);
        }
        fail("the worker never waited on the creation lock: " + worker.getState() + " "
            + Arrays.toString(worker.getStackTrace()));
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
