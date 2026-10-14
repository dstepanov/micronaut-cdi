package io.micronaut.cdi.test.noreflection;

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

/**
 * An event of a class nothing was recorded of - a class of the JDK - is of the types above the type it was fired
 * as, which the class is by construction: {@code Event<List<Duck>>} fires a {@code List<Duck>}, whatever list it is.
 * An event of a class the compilation recorded is of the types its record names.
 */
class DeclaredEventTypeWithoutReflectionTest {

    record Duck(String name) {
    }

    static class Bar<T> {
    }

    static class Baz extends Bar<List<Integer>> {
    }

    @ApplicationScoped
    static class Pond {
        final List<String> heard = new ArrayList<>();

        void ducks(@Observes List<Duck> ducks) {
            heard.add("ducks " + ducks.size());
        }

        void bars(@Observes Bar<List<Integer>> bar) {
            heard.add("bar " + bar.getClass().getSimpleName());
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @Dependent
    static class Sender {
        @Inject
        Event<List<Duck>> ducks;
        @Inject
        Event<Object> anything;
    }

    @Test
    void anEventOfAClassNothingWasRecordedOfIsOfTheTypeItWasFiredAs() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Sender sender = context.getBean(Sender.class);
            sender.ducks.fire(new ArrayList<>(List.of(new Duck("mallard"))));
            sender.anything.fire(new Baz());
            assertEquals(List.of("ducks 1", "bar Baz"), context.getBean(Pond.class).heard());
        }
    }
}
