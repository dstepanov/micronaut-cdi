package org.example.cdi.se;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * An SE bootstrap that fails as the container starts does not leave the failed container current: it is not the
 * container {@code CDI.current()} resolves to, a failure whose causes refer to each other is still reported, and what
 * was created before the failure is destroyed, the container telling nothing of a shutdown it never started.
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

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean failWithResources;

    @Singleton
    public static class Resource {
        void use() {
        }

        @PreDestroy
        void close() {
            if (failWithResources) {
                EVENTS.add("resource closed");
            }
        }
    }

    /**
     * Creates a singleton and an application scoped instance, and then fails the start.
     */
    @ApplicationScoped
    public static class FailingWithResources {
        @Inject
        Resource resource;

        void onStartup(@Observes Startup startup) {
            if (failWithResources) {
                EVENTS.add("startup");
                resource.use();
                throw new IllegalStateException("the startup failed");
            }
        }

        void onShutdown(@Observes Shutdown shutdown) {
            if (failWithResources) {
                EVENTS.add("shutdown");
            }
        }

        @PreDestroy
        void destroy() {
            if (failWithResources) {
                EVENTS.add("observer destroyed");
            }
        }
    }

    @Test
    void whatWasCreatedBeforeTheFailureIsDestroyed() {
        EVENTS.clear();
        failWithResources = true;
        try {
            assertThrows(RuntimeException.class, () -> SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(FailingWithResources.class, Resource.class).initialize());
        } finally {
            failWithResources = false;
        }
        // the singleton and the application scoped instance the start created are destroyed; the container,
        // which never told the application it started, tells it of no shutdown, and is not started again by it
        assertEquals(Set.of("startup", "resource closed", "observer destroyed"), Set.copyOf(EVENTS));
        assertEquals(1, EVENTS.stream().filter("startup"::equals).count(), EVENTS::toString);
        assertEquals(3, EVENTS.size(), EVENTS::toString);
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
