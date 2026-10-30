package org.example.cdi.se;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * An SE bootstrap that fails as the container starts leaves nothing of the failed container behind: it is not the
 * container {@code CDI.current()} resolves to, and a failure whose causes refer to each other is still reported.
 */
class SeFailedStartupTest {

    /**
     * What the startup observer throws, armed by the test that wants it.
     */
    static final AtomicReference<Supplier<RuntimeException>> FAILURE = new AtomicReference<>();

    /**
     * Fails the start of the container of the test that armed it.
     */
    @ApplicationScoped
    public static class FailingStartup {

        void onStartup(@Observes Startup startup) {
            Supplier<RuntimeException> failure = FAILURE.getAndSet(null);
            if (failure != null) {
                throw failure.get();
            }
        }
    }

    @Test
    void aContainerThatFailedToStartIsNotTheCurrentOne() {
        BeanManager before = currentBeanManager();
        FAILURE.set(() -> new IllegalStateException("the startup failed"));
        assertThrows(RuntimeException.class, () -> SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(FailingStartup.class).initialize());
        assertSame(before, currentBeanManager(), "the failed container is not left as the current one");
    }

    @Test
    void aFailureWhoseCausesReferToEachOtherIsReported() {
        FAILURE.set(() -> {
            IllegalStateException first = new IllegalStateException("first");
            IllegalStateException second = new IllegalStateException("second", first);
            first.initCause(second);
            return first;
        });
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
            assertThrows(RuntimeException.class, () -> SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(FailingStartup.class).initialize()));
    }

    private static BeanManager currentBeanManager() {
        try {
            return CDI.current().getBeanManager();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
