package org.example.cdi.se;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application bean the processor records no scope of the specification for - a plain {@code jakarta.inject}
 * singleton - is a bean of the archive it was selected into, and of no other: having no metadata of the
 * specification does not make it infrastructure.
 */
class SeUnselectedSingletonTest {

    /**
     * Selected into the archive.
     */
    @ApplicationScoped
    public static class Selected {
    }

    @Test
    void anUnselectedSingletonIsNotInAnArchiveWithoutDiscovery() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Selected.class).initialize()) {
            assertTrue(container.select(Selected.class).isResolvable());
            assertTrue(container.select(UnselectedAppSingleton.class).isUnsatisfied(),
                "an application singleton the program did not select is not a bean of the archive");
        }
    }

    @Test
    void aSingletonOffTheRestrictedClasspathIsNotABean() {
        MicronautSeContainerInitializer.restrictClasspath(Set.of(SeUnselectedSingletonTest.class.getName()));
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            assertTrue(container.select(Selected.class).isResolvable());
            assertTrue(container.select(UnselectedAppSingleton.class).isUnsatisfied(),
                "an application singleton off the classpath of the deployment is not a bean of it");
        } finally {
            MicronautSeContainerInitializer.restrictClasspath(null);
        }
    }
}
