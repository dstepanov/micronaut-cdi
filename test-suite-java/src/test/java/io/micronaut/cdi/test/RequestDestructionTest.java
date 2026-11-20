package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The beans of a request are destroyed while the request context is still active: a bean that uses another bean of
 * the request as it is destroyed reaches it, whichever way the request was begun. One bean failing as it is
 * destroyed does not keep the others from being destroyed. A request the container destroys as it closes is told
 * so as one that ends is, once, and the thread it was activated on is left with nothing of it.
 */
class RequestDestructionTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean recording;
    static volatile @Nullable CountDownLatch beforeDestroyedReached;
    static volatile @Nullable CountDownLatch beforeDestroyedRelease;
    static volatile @Nullable CountDownLatch initializedReached;
    static volatile @Nullable CountDownLatch initializedRelease;

    @ApplicationScoped
    public static class Lifecycle {
        void initialized(@Observes @Initialized(RequestScoped.class) Object event) throws InterruptedException {
            CountDownLatch reached = initializedReached;
            CountDownLatch release = initializedRelease;
            if (recording && reached != null && release != null) {
                EVENTS.add("initialized");
                reached.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
        }

        void beforeDestroyed(@Observes @BeforeDestroyed(RequestScoped.class) Object event) throws InterruptedException {
            if (recording) {
                EVENTS.add("before destroyed");
                CountDownLatch reached = beforeDestroyedReached;
                CountDownLatch release = beforeDestroyedRelease;
                if (reached != null && release != null) {
                    reached.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
            }
        }

        void destroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
            if (recording) {
                EVENTS.add("destroyed");
            }
        }
    }

    /**
     * Observers of a request's lifecycle told after {@link Lifecycle}'s, in a bean of a normal scope: a notification
     * still under way as the container closes reaches them only through the stopped context, which tells no more of
     * them rather than failing the request's work.
     */
    @ApplicationScoped
    public static class LateLifecycle {
        void initialized(@Observes @Priority(Interceptor.Priority.APPLICATION + 900) @Initialized(RequestScoped.class)
                         Object event) {
            if (recording && initializedReached != null) {
                EVENTS.add("late initialized");
            }
        }

        void beforeDestroyed(@Observes @Priority(Interceptor.Priority.APPLICATION + 900)
                             @BeforeDestroyed(RequestScoped.class) Object event) {
            if (recording && beforeDestroyedReached != null) {
                EVENTS.add("late before destroyed");
            }
        }
    }

    @RequestScoped
    public static class Journal {
        public void note(String entry) {
            EVENTS.add(entry);
        }
    }

    @RequestScoped
    public static class Session {
        @Inject
        Journal journal;

        public void open() {
        }

        @PreDestroy
        void close() {
            // the journal is a bean of the same request, reached through its client proxy
            journal.note("session closed");
        }
    }

    @RequestScoped
    public static class Ticket {
        public void open() {
        }

        @PreDestroy
        void close() {
            EVENTS.add("ticket closed");
        }
    }

    @RequestScoped
    public static class Faulty {
        public void open() {
        }

        @PreDestroy
        void close() {
            EVENTS.add("faulty closed");
            throw new IllegalStateException("cannot close");
        }
    }

    @Dependent
    public static class Activated {
        @Inject
        Session session;

        @ActivateRequestContext
        public void work() {
            session.open();
        }
    }

    @BeforeEach
    void reset() {
        EVENTS.clear();
    }

    @AfterEach
    void stopRecording() {
        recording = false;
        beforeDestroyedReached = null;
        beforeDestroyedRelease = null;
        initializedReached = null;
        initializedRelease = null;
    }

    @Test
    void aRequestRunAsWorkDestroysItsBeansWhileItIsActive() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);

            scope.run(() -> context.getBean(Session.class).open());
            assertEquals(List.of("session closed"), EVENTS);

            EVENTS.clear();
            scope.supply(() -> {
                context.getBean(Session.class).open();
                return "done";
            });
            assertEquals(List.of("session closed"), EVENTS);

            EVENTS.clear();
            scope.call(() -> {
                context.getBean(Session.class).open();
                return "done";
            });
            assertEquals(List.of("session closed"), EVENTS);
        }
    }

    @Test
    void aRequestTheControllerEndsDestroysItsBeansWhileItIsActive() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController controller = context.getBean(RequestContextController.class);

            controller.activate();
            context.getBean(Session.class).open();
            controller.deactivate();

            assertEquals(List.of("session closed"), EVENTS);
        }
    }

    @Test
    void aRequestActivatedForAMethodDestroysItsBeansWhileItIsActive() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Activated.class).work();

            assertEquals(List.of("session closed"), EVENTS);
        }
    }

    @Test
    void aRequestLeftOpenIsDestroyedWithTheContainerWhileItIsActive() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestContextController.class).activate();
            context.getBean(Session.class).open();
        }
        assertEquals(List.of("session closed"), EVENTS);
    }

    @Test
    void aRequestRunAsWorkOnAnotherThreadIsDestroyedWithTheContainerOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work;
            try (ApplicationContext context = ApplicationContext.run()) {
                RequestScope scope = context.getBean(RequestScope.class);
                work = executor.submit(() -> scope.run(() -> {
                    context.getBean(Session.class).open();
                    started.countDown();
                    try {
                        // still running as the container closes
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertTrue(started.await(10, TimeUnit.SECONDS));
            }
            // the container destroyed the request of the work that had not returned
            assertEquals(List.of("session closed"), EVENTS);

            release.countDown();
            work.get(10, TimeUnit.SECONDS);
            // and the work's own end did not destroy it again
            assertEquals(List.of("session closed"), EVENTS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oneBeanFailingToBeDestroyedLeavesTheOthersToBeDestroyed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestScope.class).run(() -> {
                context.getBean(Faulty.class).open();
                context.getBean(Session.class).open();
            });

            assertEquals(List.of("faulty closed", "session closed"), EVENTS.stream().sorted().toList());
        }
    }

    @Test
    void aRequestLeftOpenIsToldItIsDestroyedAsTheContainerCloses() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Lifecycle.class);
            recording = true;
            context.getBean(RequestContextController.class).activate();
            context.getBean(Session.class).open();
        }
        assertEquals(List.of("before destroyed", "session closed", "destroyed"), EVENTS);
    }

    @Test
    void aRequestRunAsWorkOnAnotherThreadIsToldOnceItIsDestroyedWithTheContainer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work;
            try (ApplicationContext context = ApplicationContext.run()) {
                context.getBean(Lifecycle.class);
                RequestScope scope = context.getBean(RequestScope.class);
                work = executor.submit(() -> scope.run(() -> {
                    context.getBean(Session.class).open();
                    recording = true;
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertTrue(started.await(10, TimeUnit.SECONDS));
            }
            assertEquals(List.of("before destroyed", "session closed", "destroyed"), EVENTS);

            release.countDown();
            work.get(10, TimeUnit.SECONDS);
            // the work's own end tells nothing again
            assertEquals(List.of("before destroyed", "session closed", "destroyed"), EVENTS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aRequestEndingAsTheContainerClosesIsDestroyedByItsOwnEndOnce() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        beforeDestroyedReached = reached;
        beforeDestroyedRelease = release;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work;
            try (ApplicationContext context = ApplicationContext.run()) {
                context.getBean(Lifecycle.class);
                RequestScope scope = context.getBean(RequestScope.class);
                recording = true;
                work = executor.submit(() -> scope.run(() -> context.getBean(Ticket.class).open()));
                // the work's end has begun: its observer of BeforeDestroyed is running
                assertTrue(reached.await(10, TimeUnit.SECONDS));
            }
            // the end of the request claimed it before telling its observers, so closing the container left its
            // beans to that end rather than destroying them under the observer still running
            assertEquals(List.of("before destroyed"), EVENTS);

            release.countDown();
            work.get(10, TimeUnit.SECONDS);
            // destroyed once, by the end; the context it would tell it was destroyed has stopped
            assertEquals(List.of("before destroyed", "ticket closed"), EVENTS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aRequestTheContainerDestroysWhileItsObserversAreToldItBeganIsToldItWasDestroyed() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        initializedReached = reached;
        initializedRelease = release;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work;
            try (ApplicationContext context = ApplicationContext.run()) {
                context.getBean(Lifecycle.class);
                RequestScope scope = context.getBean(RequestScope.class);
                recording = true;
                work = executor.submit(() -> scope.run(() -> EVENTS.add("work")));
                // the observer of Initialized is running as the container closes
                assertTrue(reached.await(10, TimeUnit.SECONDS));
            }
            // a request whose observers were told it was initialized is told it was destroyed, by the container
            assertEquals(List.of("initialized", "before destroyed", "destroyed"), EVENTS);

            release.countDown();
            work.get(10, TimeUnit.SECONDS);
            // and its own end tells nothing again
            assertEquals(List.of("initialized", "before destroyed", "destroyed", "work"), EVENTS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aThreadWhoseActivatedRequestTheContainerDestroyedIsLeftWithNothingOfIt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RequestScope scope;
            Object initiator = new Object();
            try (ApplicationContext context = ApplicationContext.run()) {
                scope = context.getBean(RequestScope.class);
                RequestScope activated = scope;
                assertTrue(executor.submit(() -> activated.activate()).get(10, TimeUnit.SECONDS));
            }
            RequestScope closed = scope;
            List<PropagatedContextElement> left = executor.submit(() -> {
                assertThrows(ContextNotActiveException.class, closed::deactivate);
                return PropagatedContext.getOrEmpty().getAllElements();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), left);

            try (ApplicationContext context = ApplicationContext.run()) {
                scope = context.getBean(RequestScope.class);
                RequestScope activated = scope;
                assertTrue(executor.submit(() -> activated.activate(initiator)).get(10, TimeUnit.SECONDS));
            }
            RequestScope closedAgain = scope;
            left = executor.submit(() -> {
                assertFalse(closedAgain.deactivate(initiator));
                return PropagatedContext.getOrEmpty().getAllElements();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), left);
        } finally {
            executor.shutdownNow();
        }
    }
}
