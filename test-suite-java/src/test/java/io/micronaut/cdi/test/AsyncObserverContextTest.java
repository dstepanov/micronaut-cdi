package io.micronaut.cdi.test;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Section 2.8.5.3: an asynchronous observer method is called in a new lifecycle context. The request context it is
 * notified in is one of its own - not the one of whoever fired the event, whichever thread the executor runs the
 * notification on - and it is destroyed when the notification completes, while still active.
 */
class AsyncObserverContextTest {

    static final AtomicInteger IDS = new AtomicInteger();
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile int observed;

    /** The event, so that no other observer of the suite hears it. */
    public record Ping(String text) {
    }

    @RequestScoped
    public static class RequestJournal {
        public void note(String entry) {
            EVENTS.add(entry);
        }
    }

    @RequestScoped
    public static class RequestState {
        final int id = IDS.incrementAndGet();

        @Inject
        RequestJournal journal;

        public int id() {
            return id;
        }

        @PreDestroy
        void destroyed() {
            // another bean of the same request, reached while the request is destroyed
            journal.note("state " + id + " destroyed");
        }
    }

    @Singleton
    public static class Listener {
        @Inject
        RequestState state;

        void on(@ObservesAsync Ping ping) {
            observed = state.id();
            EVENTS.add("observed in " + observed);
        }
    }

    @BeforeEach
    void reset() {
        EVENTS.clear();
    }

    private static void fire(ApplicationContext context, NotificationOptions options) throws Exception {
        BeanManager manager = context.getBean(BeanManager.class);
        manager.getEvent().select(Ping.class).fireAsync(new Ping("ping"), options)
            .toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void anObserverNotifiedOnTheFiringThreadHasARequestOfItsOwn() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestScope.class).call(() -> {
                int caller = context.getBean(RequestState.class).id();

                fire(context, NotificationOptions.ofExecutor(Runnable::run));

                assertEquals(List.of("observed in " + observed, "state " + observed + " destroyed"), EVENTS);
                assertNotEquals(caller, observed);
                // the request of the caller is the active one again, with the instance it had
                assertEquals(caller, context.getBean(RequestState.class).id());
                return null;
            });
        }
    }

    @Test
    void anObserverNotifiedOnAnotherThreadHasARequestOfItsOwn() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestScope.class).call(() -> {
                int caller = context.getBean(RequestState.class).id();

                fire(context, NotificationOptions.ofExecutor(pool));

                assertEquals(List.of("observed in " + observed, "state " + observed + " destroyed"), EVENTS);
                assertNotEquals(caller, observed);
                assertEquals(caller, context.getBean(RequestState.class).id());
                return null;
            });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anObserverNotifiedByTheDefaultExecutorHasARequestThatEndsWithTheNotification() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            manager.getEvent().select(Ping.class).fireAsync(new Ping("ping"))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertEquals(List.of("observed in " + observed, "state " + observed + " destroyed"), EVENTS);
        }
    }
}
