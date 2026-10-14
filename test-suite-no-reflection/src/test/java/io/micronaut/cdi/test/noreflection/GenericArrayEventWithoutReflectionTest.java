package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A type variable of the class of an event that its supertype uses as the component of an array, {@code T[]} or
 * {@code List<T>[]}, is resolved from the type the event was fired as through the array, with nothing read from
 * a class.
 */
class GenericArrayEventWithoutReflectionTest {

    interface Payload<X> {
    }

    static class ArrayPayload<T> implements Payload<T[]> {
    }

    static class ListArrayPayload<T> implements Payload<List<T>[]> {
    }

    @ApplicationScoped
    static class Receiver {
        final List<Type> heard = new ArrayList<>();

        List<Type> heard() {
            return List.copyOf(heard);
        }

        void strings(@Observes Payload<String[]> payload, EventMetadata metadata) {
            heard.add(metadata.getType());
        }

        void lists(@Observes Payload<List<String>[]> payload, EventMetadata metadata) {
            heard.add(metadata.getType());
        }
    }

    @jakarta.enterprise.context.Dependent
    static class Sender {
        @Inject
        Event<Payload<String[]>> strings;
        @Inject
        Event<Payload<List<String>[]>> lists;
    }

    @Test
    void aVariableUsedAsTheComponentOfAnArrayIsResolvedThroughTheArray() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Sender sender = context.getBean(Sender.class);
            sender.strings.fire(new ArrayPayload<>());
            sender.lists.fire(new ListArrayPayload<>());
            List<Type> heard = context.getBean(Receiver.class).heard();
            assertEquals(2, heard.size(), heard::toString);
            ParameterizedType first = assertInstanceOf(ParameterizedType.class, heard.get(0));
            assertEquals(ArrayPayload.class, first.getRawType());
            assertEquals(String.class, first.getActualTypeArguments()[0]);
            ParameterizedType second = assertInstanceOf(ParameterizedType.class, heard.get(1));
            assertEquals(ListArrayPayload.class, second.getRawType());
            assertEquals(String.class, second.getActualTypeArguments()[0]);
        }
    }

}
