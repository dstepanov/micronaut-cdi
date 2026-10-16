package io.micronaut.cdi.test.noreflection;

import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type index holds the classes an event can be of, and the classes the recorded types name as their type
 * arguments and bounds, the JDK's among them, which is what a bound is compared against: an event of
 * {@code Crate<Integer>} is within {@code Crate<? extends Comparable<Integer>>} because {@code Integer} is a
 * {@code Comparable<Integer>}. An interface, an abstract class and an annotation literal are never the class of an
 * event object, and are not recorded for themselves.
 */
class ReferencedTypesWithoutReflectionTest {

    static class Crate<T> {
    }

    interface Stamp<S> {
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Sealed {
    }

    static final class SealedLiteral extends AnnotationLiteral<Sealed> implements Sealed {
    }

    @ApplicationScoped
    static class Inspector {
        final List<String> heard = new ArrayList<>();

        void comparable(@Observes Crate<? extends Comparable<Integer>> crate) {
            heard.add("comparable");
        }

        void stamped(@Observes Stamp<String> stamp) {
            heard.add("stamped");
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @Dependent
    static class Shipper {
        @Inject
        Event<Crate<Integer>> crates;
    }

    @Test
    void aBoundIsComparedWithTheRecordOfTheClassItNames() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Shipper.class).crates.fire(new Crate<>());
            assertEquals(List.of("comparable"), context.getBean(Inspector.class).heard());
            assertTrue(TypeHierarchies.find(Integer.class).isPresent(), "a class a bound names is recorded");
            assertFalse(TypeHierarchies.find(Stamp.class).isPresent(), "an interface is no class of an event object");
            assertFalse(TypeHierarchies.find(SealedLiteral.class).isPresent(), "an annotation literal is no event");
        }
    }
}
