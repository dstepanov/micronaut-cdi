package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LifecycleEventTest {

    static final List<String> SEEN = new ArrayList<>();

    @ApplicationScoped
    static class Watcher {

        void onStartup(@Observes Startup startup) {
            SEEN.add("startup");
        }

        void onInitialized(@Observes @Initialized(ApplicationScoped.class) Object event) {
            SEEN.add("initialized");
        }

        void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            SEEN.add("beforeDestroyed");
        }

        void onShutdown(@Observes Shutdown shutdown) {
            SEEN.add("shutdown");
        }

        void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            SEEN.add("destroyed");
        }
    }

    @Test
    void theContainerFiresTheEventsOfItsOwnLifecycle() {
        SEEN.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(List.of("initialized", "startup"), SEEN);
        }
        assertEquals(List.of("initialized", "startup", "shutdown", "beforeDestroyed", "destroyed"), SEEN);
    }
}
