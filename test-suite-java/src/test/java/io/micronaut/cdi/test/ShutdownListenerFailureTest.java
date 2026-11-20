package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A listener of Micronaut's shutdown event that throws, and so stops Micronaut telling the listeners after it, does
 * not take the container's way down with it: the application is still told it shuts down, and its application
 * context is destroyed between its two events.
 */
class ShutdownListenerFailureTest {

    /**
     * The beans here are of the contexts of this test alone.
     */
    static final String DEPLOYED = "shutdown-listener-failure-test.deployed";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Singleton
    @Requires(property = DEPLOYED)
    public static class FailingShutdownListener implements ApplicationEventListener<ShutdownEvent> {
        @Override
        public void onApplicationEvent(ShutdownEvent event) {
            throw new IllegalStateException("the listener failed");
        }
    }

    @ApplicationScoped
    @Requires(property = DEPLOYED)
    public static class Gauge {
        public void read() {
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("gauge destroyed");
        }
    }

    @Singleton
    @Requires(property = DEPLOYED)
    public static class Recorder {
        // static, so that telling them creates nothing of a context that is going down
        static void onShutdown(@Observes Shutdown shutdown) {
            EVENTS.add("shutdown");
        }

        static void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            EVENTS.add("before destroyed");
        }

        static void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            EVENTS.add("destroyed");
        }
    }

    @Test
    void anEarlierShutdownListenerThatThrowsLeavesTheContainersWayDownToRun() {
        EVENTS.clear();
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true));
        context.getBean(Gauge.class).read();
        try {
            context.close();
        } catch (RuntimeException e) {
            // what the listener threw comes out of the stop, which goes on all the same
        }
        assertEquals(List.of("shutdown", "before destroyed", "gauge destroyed", "destroyed"), EVENTS);
    }
}
