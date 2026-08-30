package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean produced for a bean in a normal scope is disposed of when that scope ends, which for the application
 * scope is when the container shuts down.
 */
class ScopedDisposalTest {

    record Session(String id) {
    }

    @ApplicationScoped
    static class Sessions {
        static final List<String> DISPOSED = new ArrayList<>();

        @Produces
        @Dependent
        Session session() {
            return new Session("one");
        }

        void close(@Disposes Session session) {
            DISPOSED.add(session.id());
        }
    }

    @ApplicationScoped
    static class Holder {
        @Inject
        Session session;

        String use() {
            return session.id();
        }
    }

    @Test
    void aBeanProducedForAnApplicationScopedBeanIsDisposedOfWhenTheContainerStops() {
        Sessions.DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("one", context.getBean(Holder.class).use());
        }
        assertTrue(Sessions.DISPOSED.contains("one"),
            "the disposer should have run as the application scope ended, got " + Sessions.DISPOSED);
    }
}
