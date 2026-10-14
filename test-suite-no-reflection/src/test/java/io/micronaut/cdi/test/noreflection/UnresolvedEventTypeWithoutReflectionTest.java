package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Section 10.2.1: an event type that contains a type variable is refused, whether or not anything was recorded of
 * the class of the event object - it is known from the type alone.
 */
class UnresolvedEventTypeWithoutReflectionTest {

    @Dependent
    static class Sender {
        @Inject
        Event<Object> anything;
    }

    @Test
    <T> void aTypeWithATypeVariableIsRefused() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Event<Object> anything = context.getBean(Sender.class).anything;
            assertThrows(IllegalArgumentException.class, () -> anything.select(new TypeLiteral<Map<Exception, T>>() {
            }).fire(new HashMap<Exception, T>()));
        }
    }
}
