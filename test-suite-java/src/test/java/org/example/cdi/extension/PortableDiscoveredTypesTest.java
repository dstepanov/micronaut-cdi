package org.example.cdi.extension;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AfterBeanDiscovery} describes the annotated types that were discovered, and no other (CDI 4.1 section
 * 11.5.3): a class left out of the archive has none, and an id no annotated type has finds nothing.
 */
class PortableDiscoveredTypesTest {

    @Dependent
    public static class Included {
    }

    @Dependent
    public static class LeftOut {
    }

    static class Reading implements Extension {

        final List<String> read = new ArrayList<>();

        void after(@Observes AfterBeanDiscovery event) {
            read.add("included " + count(event.getAnnotatedTypes(Included.class)));
            read.add("left out " + count(event.getAnnotatedTypes(LeftOut.class)));
            read.add("by its id " + (event.getAnnotatedType(Included.class, Included.class.getName()) != null));
            read.add("by another id " + (event.getAnnotatedType(Included.class, "never-registered") != null));
            read.add("left out by id " + (event.getAnnotatedType(LeftOut.class, LeftOut.class.getName()) != null));
        }

        private static int count(Iterable<? extends AnnotatedType<?>> types) {
            int count = 0;
            for (AnnotatedType<?> ignored : types) {
                count++;
            }
            return count;
        }
    }

    @Test
    void onlyTheDiscoveredTypesAreDescribed() {
        Reading extension = new Reading();
        try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Included.class).addExtensions(extension).initialize()) {
            assertEquals(List.of("included 1", "left out 0", "by its id true", "by another id false",
                "left out by id false"), extension.read);
        }
    }
}
