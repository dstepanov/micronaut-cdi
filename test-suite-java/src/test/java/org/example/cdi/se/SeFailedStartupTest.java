package org.example.cdi.se;

import io.micronaut.cdi.internal.runtime.ContainerLifecycle;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * An SE bootstrap that fails as the container starts does not leave the failed container current: it is not the
 * container {@code CDI.current()} resolves to, a failure whose causes refer to each other is still reported, and what
 * was created before the failure is destroyed, the container telling nothing of a shutdown it never started but
 * announcing the destruction of the application context it announced was initialized.
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

        // static, so that telling them creates nothing of a context that is going down
        static void onBeforeDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
            if (failWithResources) {
                EVENTS.add("before destroyed");
            }
        }

        static void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event) {
            if (failWithResources) {
                EVENTS.add("destroyed");
            }
        }
    }

    static final AtomicBoolean FAIL_AFTER_CREATION = new AtomicBoolean();
    static final List<String> STARTED_EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean recordingStarted;

    /**
     * Fails the start once the container told the application it started: a listener of the creation of the bean
     * that tells it, which runs after the bean's {@code @PostConstruct} returned.
     */
    @Singleton
    public static class FailingAfterStartup implements BeanCreatedEventListener<ContainerLifecycle> {
        @Override
        public ContainerLifecycle onCreated(BeanCreatedEvent<ContainerLifecycle> event) {
            if (FAIL_AFTER_CREATION.getAndSet(false)) {
                throw new IllegalStateException("the creation failed after the container started");
            }
            return event.getBean();
        }

        static void onStartup(@Observes Startup startup) {
            if (recordingStarted) {
                STARTED_EVENTS.add("startup");
            }
        }

        static void onShutdown(@Observes Shutdown shutdown) {
            if (recordingStarted) {
                STARTED_EVENTS.add("shutdown");
            }
        }
    }

    @Test
    void anApplicationToldItStartedIsToldItShutsDownAsTheFailedStartIsRolledBack() {
        STARTED_EVENTS.clear();
        recordingStarted = true;
        FAIL_AFTER_CREATION.set(true);
        try {
            assertThrows(RuntimeException.class, () -> SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(FailingAfterStartup.class).initialize());
        } finally {
            recordingStarted = false;
            FAIL_AFTER_CREATION.set(false);
        }
        // Startup was fired to its end before the start failed, so the rollback is the shutdown of an application
        // that started, whichever instance of the container's lifecycle listens to it
        assertEquals(List.of("startup", "shutdown"), STARTED_EVENTS);
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
        // which never told the application it started, tells it of no shutdown, and is not started again by it.
        // The application context, which the application was told was initialized, is destroyed between its two
        // events, each told once (section 6.7.2)
        assertEquals(Set.of("startup", "before destroyed", "observer destroyed", "destroyed", "resource closed"),
            Set.copyOf(EVENTS));
        assertEquals(5, EVENTS.size(), EVENTS::toString);
        assertEquals(List.of("startup", "before destroyed", "observer destroyed", "destroyed"),
            EVENTS.stream().filter(event -> !event.equals("resource closed")).toList(),
            "the application context is destroyed between its two events: " + EVENTS);
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
