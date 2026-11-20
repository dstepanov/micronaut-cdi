package org.example.cdi.extension;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ProcessBeanAttributes} of a produced bean is of the type the producer declares, with its arguments (CDI 4.1
 * section 11.5.9): an observer of the parameterization is told of it, and an observer of another is not.
 */
class PortableParameterizedProducerTest {

    @Dependent
    public static class Shelf {
        @Produces
        List<String> titles() {
            return List.of("one");
        }
    }

    static class Observing implements Extension {

        final List<String> told = new ArrayList<>();

        void strings(@Observes ProcessBeanAttributes<List<String>> event) {
            told.add("strings");
        }

        void integers(@Observes ProcessBeanAttributes<List<Integer>> event) {
            told.add("integers");
        }
    }

    @Test
    void anObserverOfTheProducedParameterizationIsTold() {
        Observing extension = new Observing();
        try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Shelf.class).addExtensions(extension).initialize()) {
            assertEquals(List.of("strings"), extension.told);
        }
    }
}
