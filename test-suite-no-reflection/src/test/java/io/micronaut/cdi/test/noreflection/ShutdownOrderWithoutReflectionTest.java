package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order a container stops in (sections 2.5.6.2 and 2.8.6): {@code Shutdown} and the
 * {@code BeforeDestroyed} of the application context come before any bean is destroyed, and its
 * {@code Destroyed} after the application scoped beans, and what depends on them, are.
 */
class ShutdownOrderWithoutReflectionTest {

    static final List<String> SEEN = new ArrayList<>();

    @Dependent
    static class NoReflectionShutdownOrderPart {
        @PreDestroy
        void destroyed() {
            SEEN.add("part destroyed");
        }
    }

    @ApplicationScoped
    static class NoReflectionShutdownOrderResource {
        @Inject
        NoReflectionShutdownOrderPart part;

        void use() {
        }

        @PreDestroy
        void destroyed() {
            SEEN.add("resource destroyed");
        }
    }

    @Singleton
    static class NoReflectionShutdownOrderTool {
        void use() {
        }

        @PreDestroy
        void destroyed() {
            SEEN.add("tool destroyed");
        }
    }

    @Singleton
    static class NoReflectionShutdownOrderRecorder {
        void onShutdown(@Observes Shutdown shutdown) {
            SEEN.add("shutdown");
        }

        void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            SEEN.add("beforeDestroyed");
        }

        void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            SEEN.add("destroyed");
        }
    }

    @Test
    void theApplicationContextIsDestroyedBetweenItsTwoEvents() {
        SEEN.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(NoReflectionShutdownOrderResource.class).use();
            context.getBean(NoReflectionShutdownOrderTool.class).use();
        }
        assertEquals(List.of("shutdown", "beforeDestroyed"), SEEN.subList(0, 2),
            "nothing is destroyed before the container says it is stopping: " + SEEN);
        int beforeDestroyed = SEEN.indexOf("beforeDestroyed");
        int destroyed = SEEN.indexOf("destroyed");
        int resource = SEEN.indexOf("resource destroyed");
        int part = SEEN.indexOf("part destroyed");
        assertTrue(beforeDestroyed < resource && resource < destroyed,
            "the application scoped bean is destroyed between the two events: " + SEEN);
        assertTrue(beforeDestroyed < part && part < destroyed,
            "and so is what depends on it: " + SEEN);
        assertTrue(SEEN.indexOf("tool destroyed") > beforeDestroyed,
            "a singleton is destroyed after the container said it is stopping: " + SEEN);
        assertEquals(1, SEEN.stream().filter("destroyed"::equals).count(), SEEN.toString());
    }
}
