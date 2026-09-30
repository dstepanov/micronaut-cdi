package io.micronaut.cdi.test;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Section 2.8.6.2: {@code Shutdown} is observed as the container is about to shut down. The dependent instances
 * obtained through an SE container are still there for its observers, and for the ones of the
 * {@code BeforeDestroyed} of the application context; they are destroyed after, before the application context is.
 */
class SeContainerShutdownOrderTest {

    static final List<String> EVENTS = new ArrayList<>();

    @ApplicationScoped
    public static class Archive {
        public void record(String entry) {
            EVENTS.add(entry);
        }

        @PreDestroy
        void closed() {
            EVENTS.add("archive destroyed");
        }
    }

    @Dependent
    public static class Tool {
        @Inject
        Archive archive;

        @PreDestroy
        void close() {
            archive.record("tool destroyed");
        }
    }

    @Dependent
    public static class Watcher {
        void onShutdown(@Observes Shutdown shutdown) {
            EVENTS.add("shutdown");
        }

        void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            EVENTS.add("before destroyed");
        }

        void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            EVENTS.add("destroyed");
        }
    }

    @Test
    void whatWasLookedUpThroughTheContainerIsDestroyedAfterItSaysItIsStopping() {
        EVENTS.clear();
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            assertNotNull(container.select(Tool.class).get());
        }
        assertEquals(List.of("shutdown", "before destroyed", "tool destroyed", "archive destroyed", "destroyed"),
            EVENTS);
    }
}
