package io.micronaut.cdi.test.noreflection.producedfield;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.TypeHierarchies;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classes a producer field's type names as its arguments are recorded from the class that declares the field, as
 * a producer method's are: {@code Short} of a produced {@code Deque<Short>} is what a bound such as
 * {@code ? extends Comparable<Short>} is compared with, and nothing else in this package records it.
 */
class ProducerFieldReferencesWithoutReflectionTest {

    @Dependent
    public static class Numbers {
        @Produces
        java.util.Deque<Short> numbers = new java.util.ArrayDeque<>(List.of((short) 1, (short) 2));
    }

    @Dependent
    public static class Reader {
        @Inject
        java.util.Deque<? extends Comparable<Short>> comparables;
    }

    @Test
    void theArgumentsOfAProducedFieldAreRecordedFromItsClass() {
        String record = TypeHierarchies.recordClassName(Short.class.getName(),
            ProducerFieldReferencesWithoutReflectionTest.class.getPackageName());
        assertDoesNotThrow(() -> Class.forName(record),
            "Short, named by the produced field, is recorded into the package of the class declaring it");
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(List.of((short) 1, (short) 2), List.copyOf(context.getBean(Reader.class).comparables));
        }
    }
}
