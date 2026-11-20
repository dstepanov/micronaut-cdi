package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A request whose beans were destroyed is told it was destroyed though an observer of its imminent destruction
 * threw (CDI 4.1 section 6.7.1): the failure reaches whoever ended the request, and the observers of
 * {@code @Destroyed(RequestScoped.class)} are told all the same, at the end of the request and as the container
 * closes on it alike.
 */
class RequestDestroyedAfterFailedBeforeTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean failing;

    @Test
    void aRequestIsToldItWasDestroyedThoughAnObserverOfItsImminentDestructionThrew() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController controller = context.getBean(RequestContextController.class);
            controller.activate();
            failing = true;
            try {
                assertThrows(IllegalStateException.class, controller::deactivate);
            } finally {
                failing = false;
            }
            assertEquals(List.of("before destroyed", "destroyed"), EVENTS);
        }
    }

    @Test
    void aRequestTheContainerClosesOnIsToldItWasDestroyedThoughAnObserverThrew() {
        EVENTS.clear();
        ApplicationContext context = ApplicationContext.run();
        try {
            context.getBean(RequestContextController.class).activate();
            failing = true;
            try {
                context.close();
            } catch (RuntimeException e) {
                // what the observer threw may come out of the close
            }
        } finally {
            failing = false;
            if (context.isRunning()) {
                context.close();
            }
        }
        assertEquals(List.of("before destroyed", "destroyed"), EVENTS);
    }

    /**
     * Observes the end of a request, and fails as it is about to be destroyed while asked to.
     */
    @Singleton
    public static class Listener {

        void beforeDestroyed(@Observes @BeforeDestroyed(RequestScoped.class) Object request) {
            if (failing) {
                EVENTS.add("before destroyed");
                throw new IllegalStateException("refused");
            }
        }

        void destroyed(@Observes @Destroyed(RequestScoped.class) Object request) {
            if (failing) {
                EVENTS.add("destroyed");
            }
        }
    }
}
