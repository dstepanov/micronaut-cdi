package io.micronaut.cdi.test;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * An SE container is a lookup of its beans, and the dependent instances obtained through it belong to it: closing
 * the container destroys the ones the program did not destroy itself, once, while the beans they use are still
 * there.
 */
class SeContainerCloseTest {

    static final List<String> EVENTS = new ArrayList<>();

    @ApplicationScoped
    public static class Ledger {
        public void record(String entry) {
            EVENTS.add(entry);
        }

        @PreDestroy
        void closed() {
            EVENTS.add("ledger destroyed");
        }
    }

    @Dependent
    public static class LookedUpResource {
        @Inject
        Ledger ledger;

        @PreDestroy
        void close() {
            // what its disposal depends on is still available
            ledger.record("resource destroyed");
        }
    }

    @Test
    void closingTheContainerDestroysTheDependentsLookedUpThroughIt() {
        EVENTS.clear();
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            assertNotNull(container.select(LookedUpResource.class).get());
            assertNotNull(container.select(LookedUpResource.class).get());
            assertEquals(List.of(), EVENTS);
        }
        assertEquals(List.of("resource destroyed", "resource destroyed", "ledger destroyed"), EVENTS);
    }

    @Test
    void aDependentTheProgramDestroyedIsNotDestroyedAgain() {
        EVENTS.clear();
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            Instance<LookedUpResource> resources = container.select(LookedUpResource.class);
            resources.destroy(resources.get());
            assertEquals(List.of("resource destroyed"), EVENTS);
        }
        assertEquals(List.of("resource destroyed", "ledger destroyed"), EVENTS);
    }
}
