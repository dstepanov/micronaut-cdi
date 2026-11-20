package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.control.RequestContextController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pooled thread that activated a request through the specification's {@link RequestContextController}, whose
 * request the container destroyed as it closed, is left with nothing of it once it deactivates through the same
 * controller: the controller is told the context is not active, and the activation, which would keep the closed
 * container reachable from the thread, is gone.
 */
class ControllerAfterShutdownTest {

    @Test
    void aControllerDeactivatingARequestTheContainerDestroyedLeavesTheThreadWithNothingOfIt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RequestContextController controller;
            try (ApplicationContext context = ApplicationContext.run()) {
                controller = context.getBean(RequestContextController.class);
                RequestContextController activating = controller;
                assertTrue(executor.submit(activating::activate).get(10, TimeUnit.SECONDS));
            }
            RequestContextController closed = controller;
            List<PropagatedContextElement> left = executor.submit(() -> {
                assertThrows(ContextNotActiveException.class, closed::deactivate);
                return PropagatedContext.getOrEmpty().getAllElements();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), left);
        } finally {
            executor.shutdownNow();
        }
    }
}
