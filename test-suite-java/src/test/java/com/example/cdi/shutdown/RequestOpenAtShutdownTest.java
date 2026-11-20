package com.example.cdi.shutdown;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request still open as the container closes is destroyed before the application context and before any
 * singleton: a request scoped bean whose destruction uses an application scoped bean or a singleton finds the
 * instances it used while the request ran, and the observers of the request's end are told before the application
 * context says it is going.
 *
 * <p>The classes are in a package whose definitions sort before the container's own, so that nothing here holds
 * by the order of definition names in which Micronaut destroys singletons with no dependency between them.</p>
 */
class RequestOpenAtShutdownTest {

    /**
     * The beans here are of the contexts of this test alone: an observer of the end of every request would be told
     * of the requests of other tests too.
     */
    static final String DEPLOYED = "request-open-at-shutdown-test.deployed";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final AtomicInteger AUDITS = new AtomicInteger();
    static final AtomicInteger LEDGERS = new AtomicInteger();
    static volatile boolean recording;

    @ApplicationScoped
    @Requires(property = DEPLOYED)
    public static class Audit {
        // numbered as the instance is created, which its client proxy is not
        private volatile int number;
        private volatile boolean destroyed;

        @PostConstruct
        void created() {
            number = AUDITS.incrementAndGet();
        }

        public void write(String entry) {
            EVENTS.add(entry + " to audit " + number + (destroyed ? " after it was destroyed" : ""));
        }

        @PreDestroy
        void destroy() {
            destroyed = true;
            EVENTS.add("audit " + number + " destroyed");
        }
    }

    @Singleton
    @Requires(property = DEPLOYED)
    public static class Ledger {
        // numbered as the instance is created, which its client proxy is not
        private volatile int number;
        private volatile boolean destroyed;

        @PostConstruct
        void created() {
            number = LEDGERS.incrementAndGet();
        }

        public void write(String entry) {
            EVENTS.add(entry + " to ledger " + number + (destroyed ? " after it was destroyed" : ""));
        }

        @PreDestroy
        void destroy() {
            destroyed = true;
            EVENTS.add("ledger " + number + " destroyed");
        }
    }

    @RequestScoped
    @Requires(property = DEPLOYED)
    public static class Cart {
        @Inject
        Audit audit;
        @Inject
        Ledger ledger;

        public void open() {
            audit.write("cart opened");
            ledger.write("cart opened");
        }

        @PreDestroy
        void flush() {
            audit.write("cart flushed");
            ledger.write("cart flushed");
        }
    }

    @ApplicationScoped
    @Requires(property = DEPLOYED)
    public static class Watcher {
        public void touch() {
        }

        void requestGoing(@Observes @BeforeDestroyed(RequestScoped.class) Object event) {
            if (recording) {
                EVENTS.add("request before destroyed");
            }
        }

        void requestGone(@Observes @Destroyed(RequestScoped.class) Object event) {
            if (recording) {
                EVENTS.add("request destroyed");
            }
        }

        void applicationGoing(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            if (recording) {
                EVENTS.add("application before destroyed");
            }
        }
    }

    @BeforeEach
    void reset() {
        EVENTS.clear();
        AUDITS.set(0);
        LEDGERS.set(0);
    }

    @AfterEach
    void stopRecording() {
        recording = false;
    }

    private static final List<String> IN_ORDER = List.of(
        "cart opened to audit 1", "cart opened to ledger 1",
        "request before destroyed", "cart flushed to audit 1", "cart flushed to ledger 1", "request destroyed",
        "application before destroyed", "audit 1 destroyed", "ledger 1 destroyed");

    @Test
    void aRequestLeftOpenOnTheClosingThreadIsDestroyedBeforeTheApplicationContext() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true))) {
            context.getBean(Watcher.class).touch();
            recording = true;
            context.getBean(RequestContextController.class).activate();
            context.getBean(Cart.class).open();
        }
        assertEquals(IN_ORDER, EVENTS);
    }

    @Test
    void aRequestLeftOpenOnAnotherThreadIsDestroyedBeforeTheApplicationContext() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true))) {
                context.getBean(Watcher.class).touch();
                recording = true;
                executor.submit(() -> {
                    context.getBean(RequestContextController.class).activate();
                    context.getBean(Cart.class).open();
                }).get(10, TimeUnit.SECONDS);
            }
            assertEquals(IN_ORDER, EVENTS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
