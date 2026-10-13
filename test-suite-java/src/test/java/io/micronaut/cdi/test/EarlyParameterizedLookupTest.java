package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.exceptions.NoSuchBeanException;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A parameterized lookup is matched by the rules of section 2.4.2.1 from the first lookup a context makes: a bean
 * whose type variable is bounded by a parameterized type matches a required argument within the bound, and no other.
 * The resolution customizer is handed the context it serves before it resolves anything, rather than the generic
 * hierarchies the processor recorded as the first lifecycle event goes through the container, and a builder that
 * builds two contexts serves each with a customizer of its own.
 */
class EarlyParameterizedLookupTest {

    public interface Rack<T> {
    }

    /**
     * Comparable to itself through what it implements, which the bound of the rack asks.
     */
    public static class Volume implements Comparable<Volume> {
        @Override
        public int compareTo(Volume o) {
            return 0;
        }
    }

    @Dependent
    public static class SortedRack<T extends Comparable<T>> implements Rack<T> {
    }

    @Test
    void aParameterizedLookupMadeFirstIsMatchedByTheRecordedHierarchies() {
        try (ApplicationContext context = ApplicationContext.run()) {
            // the first thing asked of the context
            assertInstanceOf(SortedRack.class, context.getBean(Argument.of(Rack.class, Volume.class)));
            assertThrows(NoSuchBeanException.class, () -> context.getBean(Argument.of(Rack.class, Object.class)),
                "an Object is not comparable, and outside the bound of the rack");
        }
    }

    @Test
    void eachContextOfOneBuilderIsServedByItsOwnCustomizer() {
        ApplicationContextBuilder builder = ApplicationContext.builder();
        builder.build().start().close();
        try (ApplicationContext second = builder.build().start()) {
            assertInstanceOf(SortedRack.class, second.getBean(Argument.of(Rack.class, Volume.class)));
        }
    }
}
