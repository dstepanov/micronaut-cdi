package org.example.cdi.se;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The highest priority decides among the alternatives left only where every one of them has a priority (CDI 4.1
 * section 5.2.2): an alternative the SE bootstrap selected without one, beside one that has a priority, leaves the
 * dependency ambiguous, for a lookup and at an injection point alike.
 */
class SeMixedAlternativePriorityTest {

    public interface Service {
    }

    @Dependent
    public static class Plain implements Service {
    }

    @Dependent
    @Alternative
    public static class Selected implements Service {
    }

    @Dependent
    @Alternative
    @Priority(10)
    public static class Prioritized implements Service {
    }

    @Dependent
    public static class Consumer {
        @Inject
        Service service;
    }

    @Test
    void aLookupAmongAPrioritizedAndAnUnprioritizedAlternativeIsAmbiguous() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Plain.class, Selected.class, Prioritized.class)
            .selectAlternatives(Selected.class).initialize()) {
            Instance<Service> services = container.select(Service.class);
            assertTrue(services.isAmbiguous());
            assertThrows(AmbiguousResolutionException.class, services::get);
        }
    }

    @Test
    void anInjectionPointAmongAPrioritizedAndAnUnprioritizedAlternativeIsADeploymentProblem() {
        assertThrows(DeploymentException.class, () -> {
            try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(Plain.class, Selected.class, Prioritized.class, Consumer.class)
                .selectAlternatives(Selected.class).initialize()) {
                // the start alone has to fail
            }
        });
    }

    @Test
    void thePrioritizedAlternativeIsSelectedWhereItIsTheOnlyAlternativeLeft() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Plain.class, Selected.class, Prioritized.class).initialize()) {
            assertEquals(Prioritized.class, container.select(Service.class).get().getClass());
        }
    }
}
