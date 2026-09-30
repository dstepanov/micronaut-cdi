package io.micronaut.cdi.test.event;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Section 9.3.1 matches a parameterized observed type against the event type of the same raw type.
 * {@code Child<Integer>} is a {@code Parent<String>}, so an observer of {@code Parent<Integer>} does not observe it.
 */
class EventRawTypeMatchingTest {

    static final List<String> OBSERVED = Collections.synchronizedList(new ArrayList<>());

    public static class Parent<T> {
    }

    public static class Child<T> extends Parent<String> {
    }

    @ApplicationScoped
    static class Observers {

        void parentOfInteger(@Observes Parent<Integer> event) {
            OBSERVED.add("Parent<Integer>");
        }

        void parentOfString(@Observes Parent<String> event) {
            OBSERVED.add("Parent<String>");
        }

        void childOfInteger(@Observes Child<Integer> event) {
            OBSERVED.add("Child<Integer>");
        }
    }

    @Dependent
    static class Firing {

        @Inject
        Event<Child<Integer>> event;
    }

    @Test
    void anObserverOfAnotherParameterizationOfTheSupertypeIsNotNotified() {
        try (ApplicationContext context = ApplicationContext.run()) {
            OBSERVED.clear();
            context.getBean(Firing.class).event.fire(new Child<>());

            assertEquals(List.of("Child<Integer>", "Parent<String>"), OBSERVED.stream().sorted().toList());
        }
    }
}
