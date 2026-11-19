package org.example.cdi.extension;

import io.micronaut.context.BeanContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lookup Micronaut makes itself - a program's {@code BeanContext.getBean} - resolves to the default bean by the
 * qualifiers the container records of each bean, a qualifier a portable extension added among them, as a lookup
 * of the container does: a bean an extension qualified is no longer a default bean for it either.
 */
class PortableQualifierDefaultBeanTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Fast {
    }

    static final AnnotationLiteral<Fast> FAST = new AnnotationLiteral<>() {
    };

    public interface Engine {
    }

    @Dependent
    public static class FastEngine implements Engine {
    }

    @Dependent
    public static class PlainEngine implements Engine {
    }

    @Dependent
    public static class Garage {
        @Inject
        BeanContext beanContext;
    }

    static class Qualifying implements Extension {
        void qualify(@Observes ProcessAnnotatedType<FastEngine> event) {
            event.configureAnnotatedType().add(FAST);
        }
    }

    @Test
    void aLookupOfMicronautResolvesTheDefaultBeanByTheQualifiersAnExtensionAdded() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(FastEngine.class, PlainEngine.class, Garage.class).addExtensions(new Qualifying()).initialize()) {
            assertEquals(PlainEngine.class, container.select(Engine.class).get().getClass(),
                "the container's own lookup");
            BeanContext beanContext = container.select(Garage.class).get().beanContext;
            assertEquals(PlainEngine.class, beanContext.getBean(Engine.class).getClass(),
                "a lookup of Micronaut's, which the resolution customizer answers");
        }
    }
}
