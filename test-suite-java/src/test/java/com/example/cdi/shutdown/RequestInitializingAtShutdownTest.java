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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
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
 * An observer of a request's initialization that is still being resolved - one of its parameters still being
 * created - as the container destroys that request and tells it so is not told the request was initialized after it
 * was told it was destroyed (section 6.7.1).
 */
class RequestInitializingAtShutdownTest {

    /**
     * The beans here are of the contexts of this test alone.
     */
    static final String DEPLOYED = "request-initializing-at-shutdown-test.deployed";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch creationBegan = new CountDownLatch(1);
    static volatile CountDownLatch creationReleased = new CountDownLatch(1);
    static volatile Thread requestThread;

    /**
     * What the observer of the initialization is handed, whose creation the container's stop overtakes.
     */
    @Dependent
    @Requires(property = DEPLOYED)
    public static class Slow {
        @PostConstruct
        void created() throws Exception {
            if (Thread.currentThread() == requestThread) {
                creationBegan.countDown();
                assertTrue(creationReleased.await(10, TimeUnit.SECONDS));
            }
        }
    }

    @Singleton
    @Requires(property = DEPLOYED)
    public static class Observers {
        static void requestBegan(@Observes @Initialized(RequestScoped.class) Object event, Slow slow) {
            if (Thread.currentThread() == requestThread) {
                EVENTS.add("request initialized");
            }
        }

        static void requestGone(@Observes @Destroyed(RequestScoped.class) Object event) throws Exception {
            Thread thread = requestThread;
            if (thread != null && Thread.currentThread() != thread) {
                EVENTS.add("request destroyed");
                // the creation the container overtook goes on now, and is waited for
                creationReleased.countDown();
                thread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    @Test
    void anObserverStillBeingResolvedIsNotToldTheRequestBeganAfterItWasDestroyed() throws Exception {
        EVENTS.clear();
        creationBegan = new CountDownLatch(1);
        creationReleased = new CountDownLatch(1);
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true));
        RequestContextController controller = context.getBean(RequestContextController.class);
        Thread thread = new Thread(() -> {
            try {
                controller.activate();
            } catch (RuntimeException e) {
                // a request the container destroyed as it began may fail to begin
            }
        });
        requestThread = thread;
        try {
            thread.start();
            assertTrue(creationBegan.await(10, TimeUnit.SECONDS), "the observer is being resolved");
            context.close();
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            requestThread = null;
        }
        assertEquals(List.of("request destroyed"), EVENTS);
    }
}
