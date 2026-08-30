package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Urgent {
    }

    @SuppressWarnings("all")
    static final class UrgentLiteral extends AnnotationLiteral<Urgent> implements Urgent {
    }

    record Delivery(String parcel) {
    }

    record Departure(String parcel) {
    }

    static final List<String> SEEN = new ArrayList<>();

    @ApplicationScoped
    static class Warehouse {

        void onDelivery(@Observes Delivery delivery) {
            SEEN.add("warehouse:" + delivery.parcel());
        }

        void onUrgentDelivery(@Observes @Urgent Delivery delivery) {
            SEEN.add("urgent:" + delivery.parcel());
        }

        void onAnyObject(@Observes Object anything) {
            SEEN.add("object:" + anything.getClass().getSimpleName());
        }
    }

    @ApplicationScoped
    static class Ledger {

        @Priority(1)
        void first(@Observes Departure departure) {
            SEEN.add("first");
        }

        @Priority(9000)
        void last(@Observes Departure departure) {
            SEEN.add("last");
        }
    }

    @ApplicationScoped
    static class Courier {
        void onDeliveryAsync(@ObservesAsync Delivery delivery) {
            SEEN.add("courier:" + delivery.parcel());
        }
    }

    @Singleton
    static class Dispatcher {
        @Inject
        Event<Delivery> deliveries;

        @Inject
        @Urgent
        Event<Delivery> urgentDeliveries;

        @Inject
        Event<Departure> departures;
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
        // the container fires the events of its own lifecycle as it starts, and the observer of Object here sees
        // them; what each test is about is what is fired after that
        SEEN.clear();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void anEventNotifiesTheObserversOfItsTypeAndQualifiers() {
        context.getBean(Dispatcher.class).deliveries.fire(new Delivery("book"));
        assertTrue(SEEN.contains("warehouse:book"), SEEN.toString());
        // the observer of Object observes every event, since every event is one
        assertTrue(SEEN.contains("object:Delivery"), SEEN.toString());
        // the qualified observer is not notified by an event fired without the qualifier
        assertTrue(SEEN.stream().noneMatch(s -> s.startsWith("urgent:")), SEEN.toString());
    }

    @Test
    void aQualifiedEventNotifiesTheQualifiedObserverAndTheUnqualifiedOne() {
        context.getBean(Dispatcher.class).urgentDeliveries.fire(new Delivery("meds"));
        assertTrue(SEEN.contains("urgent:meds"), SEEN.toString());
        // an observer that names no qualifier observes the event whatever it was fired with
        assertTrue(SEEN.contains("warehouse:meds"), SEEN.toString());
    }

    @Test
    void selectNarrowsTheQualifiersOfAnEvent() {
        context.getBean(Dispatcher.class).deliveries.select(new UrgentLiteral()).fire(new Delivery("keys"));
        assertTrue(SEEN.contains("urgent:keys"), SEEN.toString());
    }

    @Test
    void observersAreNotifiedInPriorityOrder() {
        context.getBean(Dispatcher.class).departures.fire(new Departure("parcel"));
        assertEquals(List.of("first", "object:Departure", "last"), SEEN);
    }

    @Test
    void anEventFiredAsynchronouslyNotifiesOnlyTheAsynchronousObservers()
        throws ExecutionException, InterruptedException {
        context.getBean(Dispatcher.class).deliveries.fireAsync(new Delivery("crate")).toCompletableFuture().get();
        assertEquals(List.of("courier:crate"), SEEN);
    }
}
