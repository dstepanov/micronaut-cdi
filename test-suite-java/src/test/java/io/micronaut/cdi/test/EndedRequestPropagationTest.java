package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that has ended stays in every propagated context captured while it was under way, as the context of
 * a task handed to an executor does. Work that runs in such a context after the request ended finds no request
 * active, rather than putting new beans into the request that was destroyed.
 */
class EndedRequestPropagationTest {

    @RequestScoped
    public static class Cart {
        public String id() {
            return "cart";
        }
    }

    @Test
    void aCapturedContextDoesNotReviveAnEndedRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            RequestContextController controller = context.getBean(RequestContextController.class);
            Cart cart = context.getBean(Cart.class);

            assertTrue(controller.activate());
            PropagatedContext captured = PropagatedContext.get();
            assertEquals("cart", cart.id());
            controller.deactivate();

            captured.propagate(() -> {
                // the request ended, though the captured context still holds it
                assertThrows(ContextNotActiveException.class, () -> manager.getContext(RequestScoped.class));
                assertThrows(ContextNotActiveException.class, cart::id);
            });
        }
    }

    @Test
    void aContainerClosedInACapturedContextOfAnEndedRequestCloses() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            ApplicationContext context = ApplicationContext.run();
            RequestContextController controller = context.getBean(RequestContextController.class);
            assertTrue(controller.activate());
            PropagatedContext captured = PropagatedContext.get();
            controller.deactivate();
            PropagatedContext outer = PropagatedContext.getOrEmpty();
            try (PropagatedContext.Scope ignored = captured.propagate()) {
                // the captured context still holds the activation, which has ended already
                context.close();
                assertTrue(PropagatedContext.get() == captured,
                    "a propagation scope the container did not open is left to whoever opened it");
            }
            assertTrue(PropagatedContext.getOrEmpty() == outer);
        });
    }

    @Test
    void aContainerClosedInACapturedContextOfASuspendedRequestCloses() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            ApplicationContext context = ApplicationContext.run();
            RequestScope scope = context.getBean(RequestScope.class);
            assertTrue(scope.activate());
            PropagatedContext captured = PropagatedContext.get();
            scope.suspend();
            try (PropagatedContext.Scope ignored = captured.propagate()) {
                context.close();
            }
        });
    }
}
