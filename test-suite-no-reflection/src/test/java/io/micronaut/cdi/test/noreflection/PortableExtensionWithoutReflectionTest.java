package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portable extension is run by the module that reads classes: without it, a bootstrap that was handed one
 * fails and names the module, and a bootstrap that was handed none starts as it always did.
 */
class PortableExtensionWithoutReflectionTest {

    public static class Unrun implements Extension {
        void before(@Observes BeforeBeanDiscovery event) {
        }
    }

    @Test
    void aBootstrapHandedAnExtensionInstanceNamesTheModuleThatRunsIt() {
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
            () -> SeContainerInitializer.newInstance().disableDiscovery().addExtensions(new Unrun()).initialize());
        assertTrue(failure.getMessage().contains("io.micronaut.cdi:micronaut-cdi-reflection"), failure.getMessage());
    }

    @Test
    void aBootstrapHandedAnExtensionClassNamesTheModuleThatRunsIt() {
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
            () -> SeContainerInitializer.newInstance().disableDiscovery().addExtensions(Unrun.class).initialize());
        assertTrue(failure.getMessage().contains("io.micronaut.cdi:micronaut-cdi-reflection"), failure.getMessage());
    }

    @Test
    void aBootstrapHandedNoExtensionStarts() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery().initialize()) {
            assertTrue(container.isRunning());
        }
    }
}
