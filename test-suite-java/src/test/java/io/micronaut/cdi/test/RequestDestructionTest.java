package io.micronaut.cdi.test;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The beans of a request are destroyed while the request context is still active: a bean that uses another bean of
 * the request as it is destroyed reaches it, whichever way the request was begun. One bean failing as it is
 * destroyed does not keep the others from being destroyed.
 */
class RequestDestructionTest {

    static final List<String> EVENTS = new ArrayList<>();

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
    void oneBeanFailingToBeDestroyedLeavesTheOthersToBeDestroyed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestScope.class).run(() -> {
                context.getBean(Faulty.class).open();
                context.getBean(Session.class).open();
            });

            assertEquals(List.of("faulty closed", "session closed"), EVENTS.stream().sorted().toList());
        }
    }
}
