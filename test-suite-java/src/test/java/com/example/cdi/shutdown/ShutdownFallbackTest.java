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
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a listener of Micronaut's shutdown event that is told before the container throws, so that the event never
 * reaches the container, the container still takes the requests still open down while every singleton their beans
 * use is there, as it does on the way down the event takes: Micronaut destroys the singletons in an order of its own,
 * by name where nothing else orders them, and a singleton of an application whose package sorts before the
 * container's would otherwise go first. The beans are in such a package on purpose.
 */
class ShutdownFallbackTest {

    /**
     * The beans here are of the contexts of this test alone.
     */
    static final String DEPLOYED = "shutdown-fallback-test.deployed";
    /**
     * Deploys the shutdown listener that throws.
     */
    static final String FAILING = "shutdown-fallback-test.failing";
    /**
     * Deploys a bean whose injection point nothing satisfies, which fails the start before the application context
     * is announced.
     */
    static final String BROKEN = "shutdown-fallback-test.broken";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean recording;

    @Singleton
    @Requires(property = DEPLOYED)
    @Requires(property = FAILING)
    public static class FailingShutdownListener implements ApplicationEventListener<ShutdownEvent> {
        @Override
        public void onApplicationEvent(ShutdownEvent event) {
            throw new IllegalStateException("the listener failed");
        }
    }

    /**
     * A singleton the container does not depend on, which a request scoped bean writes to as it is destroyed.
     */
    @Singleton
    @Requires(property = DEPLOYED)
    public static class Ledger {
        private volatile boolean closed;

        public void write(String entry) {
            EVENTS.add(entry + (closed ? " to a closed ledger" : ""));
        }

        @PreDestroy
        void close() {
            closed = true;
        }
    }

    @jakarta.enterprise.context.Dependent
    @Requires(property = DEPLOYED)
    @Requires(property = BROKEN)
    public static class Unsatisfied {
        @Inject
        Runnable missing;
    }

    @jakarta.enterprise.context.Dependent
    @Requires(property = DEPLOYED)
    public static class Note {
        @PreDestroy
        void destroyed() {
            EVENTS.add("note destroyed");
        }
    }

    @RequestScoped
    @Requires(property = DEPLOYED)
    public static class Basket {
        @Inject
        Ledger ledger;

        public void fill() {
            ledger.write("basket filled");
        }

        @PreDestroy
        void destroyed() {
            ledger.write("basket destroyed");
        }
    }

    @Singleton
    @Requires(property = DEPLOYED)
    public static class Recorder {
        // static, so that telling them creates nothing of a context that is going down
        static void onShutdown(@Observes Shutdown shutdown) {
            record("shutdown");
        }

        static void onRequestGoing(@Observes @BeforeDestroyed(RequestScoped.class) Object event) {
            record("request before destroyed");
        }

        static void onRequestGone(@Observes @Destroyed(RequestScoped.class) Object event) {
            record("request destroyed");
        }

        static void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            record("before destroyed");
        }

        static void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            record("destroyed");
        }

        private static void record(String event) {
            if (recording) {
                EVENTS.add(event);
            }
        }
    }

    @Test
    void aRequestStillOpenIsDestroyedWhileTheSingletonsItsBeansUseAreThere() {
        EVENTS.clear();
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true, FAILING, true));
        RequestContextController controller = context.getBean(RequestContextController.class);
        // a request left open on this thread, as one is left by a thread that never ends it
        controller.activate();
        context.getBean(Basket.class).fill();
        recording = true;
        try {
            context.close();
        } catch (RuntimeException e) {
            // what the listener threw comes out of the stop, which goes on all the same
        } finally {
            recording = false;
        }
        assertEquals(List.of("basket filled", "shutdown", "request before destroyed", "basket destroyed",
            "request destroyed", "before destroyed", "destroyed"), EVENTS);
    }

    @Test
    void theWayDownTheShutdownEventTakesRunsOnce() {
        EVENTS.clear();
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true));
        context.getBean(RequestContextController.class).activate();
        context.getBean(Basket.class).fill();
        recording = true;
        try {
            context.close();
        } finally {
            recording = false;
        }
        assertEquals(List.of("basket filled", "shutdown", "request before destroyed", "basket destroyed",
            "request destroyed", "before destroyed", "destroyed"), EVENTS);
    }

    @Test
    void aBeanDestroyedWhileTheContainerRunsTakesNothingDown() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true))) {
            jakarta.enterprise.inject.Instance<Object> lookup =
                context.getBean(io.micronaut.cdi.internal.runtime.CdiBeanContainer.class).createInstance();
            recording = true;
            jakarta.enterprise.inject.Instance.Handle<Note> handle = lookup.select(Note.class).getHandle();
            handle.get();
            handle.destroy();
            // the container still runs, and told nothing of a way down
            context.getBean(RequestContextController.class).activate();
            context.getBean(Basket.class).fill();
            assertEquals(List.of("note destroyed", "basket filled"), EVENTS);
            recording = false;
        } finally {
            recording = false;
        }
    }

    @Test
    void aStartThatFailsBeforeTheApplicationContextIsAnnouncedTellsNothingOfAWayDown() {
        EVENTS.clear();
        recording = true;
        try {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> ApplicationContext.run(Map.of(DEPLOYED, true, BROKEN, true)).close());
        } finally {
            recording = false;
        }
        assertEquals(List.of(), EVENTS);
    }
}
