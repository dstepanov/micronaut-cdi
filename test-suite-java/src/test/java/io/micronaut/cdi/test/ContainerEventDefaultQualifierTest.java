package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event the container hands out with {@code getEvent()} has the specified type {@code Object} and the
 * specified qualifier {@code @Default} (CDI 4.1 section 11.3.10), which an event selected from it keeps beside the
 * qualifiers it adds: an observer of the default qualifier is notified, and the event metadata names it.
 */
class ContainerEventDefaultQualifierTest {

    @Test
    void anEventSelectedFromTheContainersEventKeepsTheDefaultQualifier() {
        Listener.RECEIVED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            manager.getEvent().select(Chime.class, Red.Literal.INSTANCE).fire(new Chime());
            assertEquals(List.of("default", "red"), Listener.RECEIVED.stream().sorted().toList());
            assertTrue(Listener.metadata.getQualifiers().contains(Default.Literal.INSTANCE),
                () -> String.valueOf(Listener.metadata.getQualifiers()));
        }
    }

    /**
     * The event.
     */
    public static final class Chime {
    }

    /**
     * A qualifier of the event.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Red {

        /**
         * The literal.
         */
        final class Literal extends AnnotationLiteral<Red> implements Red {
            static final Literal INSTANCE = new Literal();
        }
    }

    /**
     * Observes the event by the default qualifier and by its own.
     */
    @Dependent
    public static class Listener {

        static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
        static volatile EventMetadata metadata;

        void onDefault(@Observes @Default Chime chime, EventMetadata eventMetadata) {
            metadata = eventMetadata;
            RECEIVED.add("default");
        }

        void onRed(@Observes @Red Chime chime) {
            RECEIVED.add("red");
        }
    }
}
