package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An observer of the request context's initialization that fails ends the request it was told about: the beans it
 * created in it are destroyed as the failure comes out, whichever way the request was begun, rather than living on
 * until the container stops.
 */
class RequestInitializationFailureTest {

    static final List<String> EVENTS = new ArrayList<>();
    static volatile boolean failing;

    @RequestScoped
    public static class Ticket {
        public void touch() {
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("ticket destroyed");
        }
    }

    @ApplicationScoped
    public static class FailingObserver {
        @Inject
        Ticket ticket;

        void initialized(@Observes @Initialized(RequestScoped.class) Object event) {
            if (failing) {
                ticket.touch();
                throw new IllegalStateException("cannot initialize");
            }
        }
    }

    @AfterEach
    void reset() {
        failing = false;
        EVENTS.clear();
    }

    @Test
    void aRequestRunAsWorkEndsWhenItsInitializationFails() {
        assertDestroyedOnFailure(scope -> scope.run(() -> EVENTS.add("work")));
    }

    @Test
    void aRequestSuppliedAsWorkEndsWhenItsInitializationFails() {
        assertDestroyedOnFailure(scope -> scope.supply(() -> EVENTS.add("work")));
    }

    @Test
    void aRequestCalledAsWorkEndsWhenItsInitializationFails() {
        assertDestroyedOnFailure(scope -> {
            try {
                scope.call((Callable<Boolean>) () -> EVENTS.add("work"));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void anActivatedRequestEndsWhenItsInitializationFails() {
        assertDestroyedOnFailure(RequestScope::activate);
    }

    private static void assertDestroyedOnFailure(Consumer<RequestScope> request) {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);
            context.getBean(FailingObserver.class);
            failing = true;
            assertThrows(IllegalStateException.class, () -> request.accept(scope));
            failing = false;
            assertEquals(List.of("ticket destroyed"), EVENTS,
                "the bean the observer created is destroyed with the request, and the work does not run");
        }
    }
}
