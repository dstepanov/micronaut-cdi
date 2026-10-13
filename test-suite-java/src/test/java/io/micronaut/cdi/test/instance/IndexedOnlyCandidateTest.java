package io.micronaut.cdi.test.instance;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Indexed;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micronaut enumerates a definition under a type it is indexed by, whether or not the bean is of that type; a
 * lookup of the specification resolves the beans that have the type among their bean types alone.
 */
class IndexedOnlyCandidateTest {

    public interface Marker {
    }

    @Dependent
    @Indexed(Marker.class)
    public static class IndexedOnly {
    }

    @Test
    void aBeanIndexedByATypeItIsNotOfIsNoCandidateForIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            assertTrue(lookup.select(Marker.class).isUnsatisfied());
            assertFalse(lookup.select(IndexedOnly.class).isUnsatisfied());
        }
    }
}
