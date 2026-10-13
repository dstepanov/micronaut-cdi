package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lookup injected into a bean may be used from that bean's {@code @PreDestroy} on another thread as the context
 * stops, where Micronaut refuses the bean's own resolver: what it creates is held by the lookup instead, and
 * destroyed with it.
 */
class ShutdownLookupOffThreadTest {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static volatile boolean armed;

    @Dependent
    public static class Helper {
        String help() {
            return "helped";
        }

        @PreDestroy
        void destroyed() {
            if (armed) {
                SEEN.add("helper destroyed");
            }
        }
    }

    @Singleton
    public static class Closer {
        @Inject
        Instance<Helper> helpers;

        @PreDestroy
        void destroyed() throws InterruptedException {
            if (!armed) {
                return;
            }
            SEEN.add("closing");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                SEEN.add(executor.submit(() -> helpers.get().help()).get());
            } catch (java.util.concurrent.ExecutionException e) {
                SEEN.add("failed: " + e.getCause());
            } finally {
                executor.shutdown();
            }
        }
    }

    @Test
    void aLookupFromAPreDestroyOnAnotherThreadCreatesAndDestroys() {
        SEEN.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Closer.class);
            armed = true;
        } finally {
            armed = false;
        }
        assertEquals(List.of("closing", "helped", "helper destroyed"), SEEN);
    }
}
