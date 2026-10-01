package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bean types of a produced array of a parameterized type, and of a raw type, are what the processor recorded:
 * the array keeps the arguments of its element type, and the supertypes of a raw type are erased.
 */
class RawAndArrayBeanTypesWithoutReflectionTest {

    interface Tray<T> {
    }

    static class Bin<T> implements Tray<T> {
    }

    static class Crate<T> {
    }

    @Dependent
    static class Producers {

        @SuppressWarnings("unchecked")
        @Produces
        Crate<String>[] crates() {
            return new Crate[]{new Crate<>()};
        }

        @SuppressWarnings("rawtypes")
        @Produces
        Bin bin() {
            return new Bin();
        }
    }

    @Test
    void anArrayOfAParameterizedTypeIsResolvedByItsArguments() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertFalse(context.findBean(CdiReflection.class).isPresent());
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            assertEquals(1, container.getBeans(Argument.of(Crate.class, String.class).arrayType()).size());
            assertTrue(container.getBeans(Argument.of(Crate.class, Integer.class).arrayType()).isEmpty());
        }
    }

    @Test
    void theSupertypesOfARawBeanTypeAreErased() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            assertEquals(1, container.getBeans(Argument.of(Tray.class)).size());
            assertTrue(container.getBeans(Argument.of(Tray.class, String.class)).isEmpty());
            assertTrue(container.getBeans(Argument.of(Bin.class, Argument.ofWildcard(Object.class, null, null, null, null, null))).isEmpty(),
                "nor does a raw bean type match a wildcard");
        }
    }
}
