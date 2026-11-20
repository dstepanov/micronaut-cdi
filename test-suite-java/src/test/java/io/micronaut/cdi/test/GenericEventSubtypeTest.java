package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An event of a class that is not generic itself, and implements a parameterization of a generic interface, is
 * observed by the observers of that parameterization alone (CDI 4.1 section 9.3.1): an {@code IntegerPayload}
 * that is a {@code Payload<Integer>} is no {@code Payload<Object>}.
 */
class GenericEventSubtypeTest {

    @Test
    void anEventIsObservedByTheParameterizationItsClassImplementsAlone() {
        Listener.RECEIVED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(BeanManager.class).getEvent().select(IntegerPayload.class).fire(new IntegerPayload());
            assertEquals(List.of("integer", "raw class"), Listener.RECEIVED.stream().sorted().toList());
        }
    }

    /**
     * A generic event type.
     *
     * @param <T> The payload
     */
    public interface Payload<T> {
    }

    /**
     * An event class that parameterizes it.
     */
    public static final class IntegerPayload implements Payload<Integer> {
    }

    /**
     * Observes the parameterizations.
     */
    @Dependent
    public static class Listener {

        static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

        void onObject(@Observes Payload<Object> payload) {
            RECEIVED.add("object");
        }

        void onInteger(@Observes Payload<Integer> payload) {
            RECEIVED.add("integer");
        }

        void onString(@Observes Payload<String> payload) {
            RECEIVED.add("string");
        }

        void onClass(@Observes IntegerPayload payload) {
            RECEIVED.add("raw class");
        }
    }
}
