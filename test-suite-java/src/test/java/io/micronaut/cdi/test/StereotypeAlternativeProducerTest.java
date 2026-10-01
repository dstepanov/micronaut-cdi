package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.UnselectedAlternative;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A producer is an alternative when a stereotype it declares is one (section 2.1.7), just as when it declares
 * {@code @Alternative} itself: nothing selected it, so it produces nothing, until its stereotype is selected.
 */
class StereotypeAlternativeProducerTest {

    @Stereotype
    @Alternative
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Substitute {
    }

    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Spare {
        final class Literal extends AnnotationLiteral<Spare> implements Spare {
        }
    }

    public static class Wheel {
    }

    @Dependent
    public static class WheelProducer {
        @Produces
        @Spare
        @Substitute
        public final Wheel spareField = new Wheel();

        @Produces
        @Spare
        @Substitute
        public Wheel spareMethod() {
            return new Wheel();
        }
    }

    @Test
    void aProducerWhoseStereotypeIsAnUnselectedAlternativeProducesNothing() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(0, manager.getBeans(Wheel.class, new Spare.Literal()).size());
        }
    }

    @Test
    void selectingTheStereotypeEnablesTheProducerMethodAndField() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            UnselectedAlternative.SELECTED_STEREOTYPES, Substitute.class.getName()))) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(2, manager.getBeans(Wheel.class, new Spare.Literal()).size());
        }
    }
}
