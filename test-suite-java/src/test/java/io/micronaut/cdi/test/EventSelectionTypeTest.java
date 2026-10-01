package io.micronaut.cdi.test;

import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An event selected as a type stated in full is fired as that type, and selecting qualifiers afterwards does not
 * forget it: the parameterization is not on the event object, so it is the selection that carries it.
 */
class EventSelectionTypeTest {

    static int heard;

    /** The element type, so that no other observer of the suite hears the event. */
    public record Entry(String text) {
    }

    @Singleton
    public static class Listener {
        void on(@Observes List<Entry> entries) {
            heard++;
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectingAQualifierKeepsTheTypeTheEventWasSelectedAs() {
        heard = 0;
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautEvent<List> event = context.getBean(CdiBeanContainer.class).getEvent()
                .select((Argument) Argument.of(List.class, Entry.class));

            event.fire(new ArrayList<Entry>());
            assertEquals(1, heard);

            event.select(Any.Literal.INSTANCE).fire(new ArrayList<Entry>());
            assertEquals(2, heard);

            event.select(AnnotationValue.builder(Any.class).build()).fire(new ArrayList<Entry>());
            assertEquals(3, heard);
        }
    }
}
