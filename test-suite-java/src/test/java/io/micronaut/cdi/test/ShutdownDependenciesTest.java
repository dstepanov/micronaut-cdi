package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the container resolves on behalf of the callbacks that run as it stops - the observers of the events it fires
 * on its way down, the {@code @PreDestroy} methods of its beans - is resolved and destroyed again like at any other
 * time: a dependent instance created for one notification or one lookup goes once that is done with.
 *
 * <p>Not only while the shutdown event is published: a singleton is destroyed after it, and what its
 * {@code @PreDestroy} looks up or fires is still resolved; and an asynchronous observer notified while the
 * container stops runs on a thread of its executor, not on the one stopping the context.</p>
 */
class ShutdownDependenciesTest {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** The event a singleton fires as it is destroyed, so that no other observer of the suite hears it. */
    public record Farewell(String from) {
    }

    /** The event fired asynchronously as the container stops. */
    public record LastCall(String from) {
    }

    @Dependent
    public static class Scratch {
        static volatile int created;
        final int id = ++created;

        @PreDestroy
        void destroyed() {
            SEEN.add("scratch " + id + " destroyed");
        }
    }

    @Dependent
    public static class Note {
        @PreDestroy
        void destroyed() {
            if (SEEN.contains("armed")) {
                SEEN.add("note destroyed");
            }
        }
    }

    @Singleton
    public static class DestroyedObserver {
        void onDestroyed(@Observes @Destroyed(ApplicationScoped.class) Object event, Note note) {
            if (SEEN.contains("armed")) {
                SEEN.add("destroyed observed");
            }
        }
    }

    @Dependent
    public static class FarewellObserver {
        @PreDestroy
        void destroyed() {
            SEEN.add("farewell observer destroyed");
        }

        void onFarewell(@Observes Farewell farewell, Scratch scratch) {
            SEEN.add("farewell from " + farewell.from() + " with scratch " + scratch.id);
        }
    }

    @Dependent
    public static class LastCallObserver {
        void onLastCall(@ObservesAsync LastCall call, Scratch scratch) {
            SEEN.add("last call from " + call.from() + " with scratch " + scratch.id);
        }
    }

    @Singleton
    public static class Leaving {
        @Inject
        Event<Farewell> farewell;
        @Inject
        Instance<Scratch> scratches;

        void use() {
        }

        @PreDestroy
        void destroyed() {
            SEEN.add("leaving looked up scratch " + scratches.get().id);
            farewell.fire(new Farewell("singleton"));
        }
    }

    @ApplicationScoped
    public static class Departing {
        @Inject
        Instance<Scratch> scratches;
        @Inject
        Event<LastCall> lastCall;

        void use() {
        }

        @PreDestroy
        void destroyed() {
            SEEN.add("departing looked up scratch " + scratches.get().id);
            try {
                lastCall.fireAsync(new LastCall("application")).toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                SEEN.add("last call failed: " + e);
            }
        }
    }

    @BeforeEach
    void reset() {
        SEEN.clear();
        Scratch.created = 0;
    }

    @Test
    void aDependentParameterOfAnObserverOfTheDestroyedApplicationContextGoesAfterTheNotification() {
        try (ApplicationContext context = ApplicationContext.run()) {
            SEEN.add("armed");
        }
        int observed = SEEN.indexOf("destroyed observed");
        assertTrue(observed > 0, SEEN.toString());
        assertEquals(observed + 1, SEEN.indexOf("note destroyed"), SEEN.toString());
    }

    @Test
    void aSingletonDestroyedAtShutdownLooksUpAndFiresWithDependentsThatGoWithIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Leaving.class).use();
        }
        assertTrue(SEEN.contains("leaving looked up scratch 1"), SEEN.toString());
        assertTrue(SEEN.contains("scratch 1 destroyed"), SEEN.toString());
        int farewell = SEEN.indexOf("farewell from singleton with scratch 2");
        assertTrue(farewell >= 0, SEEN.toString());
        assertTrue(SEEN.indexOf("scratch 2 destroyed") > farewell, SEEN.toString());
        assertTrue(SEEN.indexOf("farewell observer destroyed") > farewell, SEEN.toString());
    }

    @Test
    void anApplicationScopedBeanDestroyedAtShutdownLooksUpAndFiresAsynchronously() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Departing.class).use();
        }
        assertTrue(SEEN.contains("departing looked up scratch 1"), SEEN.toString());
        assertTrue(SEEN.contains("scratch 1 destroyed"), SEEN.toString());
        int lastCall = SEEN.indexOf("last call from application with scratch 2");
        assertTrue(lastCall >= 0, SEEN.toString());
        assertTrue(SEEN.indexOf("scratch 2 destroyed") > lastCall, SEEN.toString());
    }
}
