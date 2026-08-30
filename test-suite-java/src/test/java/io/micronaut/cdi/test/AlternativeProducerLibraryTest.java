package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The shape of the kit's BarProducer: a class that is not an alternative but carries the priority, declaring
 * producer members that are alternatives.
 */
class AlternativeProducerLibraryTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Wild {
        final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Wild> implements Wild {
        }
    }

    static class Bar {
    }

    @Priority(1100)
    @Dependent
    static class BarProducer {
        static final Bar WILD = new Bar();

        @Alternative
        @Produces
        @Wild
        public final Bar producedBar = WILD;
    }

    @Test
    void anAlternativeProducerFieldOfAPrioritisedClassIsSelected() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(Bar.class, new Wild.Literal());
            assertEquals(1, beans.size(), "expected the alternative producer field, got " + beans);
            Bean<?> bean = manager.resolve(beans);
            assertNotNull(bean);
            Object reference = manager.getReference(bean, Bar.class, manager.createCreationalContext(bean));
            assertNotNull(reference);
        }
    }
}
