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
package com.example.cdi.shutdown;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request whose own end began telling its observers it is about to be destroyed before the container began to stop
 * - and which the container therefore leaves to that end - is told it was destroyed once its beans are, however long
 * an observer of its imminent destruction holds the end up while the container stops (section 6.7.1).
 */
class RequestEndingAtShutdownTest {

    /**
     * The beans here are of the contexts of this test alone.
     */
    static final String DEPLOYED = "request-ending-at-shutdown-test.deployed";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch endBegan = new CountDownLatch(1);
    static volatile CountDownLatch endReleased = new CountDownLatch(1);
    static volatile Thread requestThread;

    @RequestScoped
    @Requires(property = DEPLOYED)
    public static class Order {
        public void place() {
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("order destroyed");
        }
    }

    @Singleton
    @Requires(property = DEPLOYED)
    public static class Observers {
        // static, so that telling them creates nothing of a context that is going down
        static void requestGoing(@Observes @BeforeDestroyed(RequestScoped.class) Object event) throws Exception {
            if (Thread.currentThread() == requestThread) {
                EVENTS.add("request before destroyed");
                endBegan.countDown();
                // held until the container, stopping meanwhile, says the application context is going
                assertTrue(endReleased.await(10, TimeUnit.SECONDS));
            }
        }

        static void requestGone(@Observes @Destroyed(RequestScoped.class) Object event) {
            if (Thread.currentThread() == requestThread) {
                EVENTS.add("request destroyed");
            }
        }

        static void applicationGoing(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event)
            throws Exception {
            Thread thread = requestThread;
            if (thread != null) {
                // the request's end goes on, and the container waits for it to finish
                endReleased.countDown();
                thread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    @Test
    void aRequestWhoseEndWasToldBeforeTheContainerStoppedIsToldItWasDestroyed() throws Exception {
        EVENTS.clear();
        endBegan = new CountDownLatch(1);
        endReleased = new CountDownLatch(1);
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true));
        RequestContextController controller = context.getBean(RequestContextController.class);
        Thread thread = new Thread(() -> {
            controller.activate();
            context.getBean(Order.class).place();
            controller.deactivate();
        });
        requestThread = thread;
        try {
            thread.start();
            assertTrue(endBegan.await(10, TimeUnit.SECONDS), "the request's end began");
            context.close();
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            requestThread = null;
        }
        assertEquals(List.of("request before destroyed", "order destroyed", "request destroyed"), EVENTS);
    }
}
