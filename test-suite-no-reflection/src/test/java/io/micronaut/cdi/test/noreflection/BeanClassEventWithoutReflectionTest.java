package io.micronaut.cdi.test.noreflection;

import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic hierarchy of the class of an event that is a bean of the specification is what its definition
 * recorded of its bean types: Micronaut's type hierarchy record of the class, which a bean class has as any other
 * class an event can be of.
 */
class BeanClassEventWithoutReflectionTest {

    interface Signal<S> {
    }

    record Order(int number) {
    }

    record Invoice(int number) {
    }

    @Dependent
    static class OrderSignal implements Signal<Order> {
    }

    @Dependent
    static class AnySignal<T> implements Signal<T> {
    }

    @ApplicationScoped
    static class Listener {
        final List<String> heard = new ArrayList<>();

        void orders(@Observes Signal<Order> signal) {
            heard.add("order " + signal.getClass().getSimpleName());
        }

        void invoices(@Observes Signal<Invoice> signal) {
            heard.add("invoice " + signal.getClass().getSimpleName());
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @Dependent
    static class Sender {
        @Inject
        Event<Object> anything;
        @Inject
        Event<Signal<Order>> orders;
    }

    @Test
    void anEventOfABeanClassIsMatchedByWhatItsDefinitionRecorded() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Sender sender = context.getBean(Sender.class);
            sender.anything.fire(new OrderSignal());
            sender.orders.fire(new AnySignal<>());
            assertEquals(List.of("order OrderSignal", "order AnySignal"), context.getBean(Listener.class).heard());
            // a bean class is recorded as any other class an event can be of
            assertTrue(TypeHierarchies.find(OrderSignal.class).isPresent());
            assertTrue(TypeHierarchies.find(AnySignal.class).isPresent());
        }
    }
}
