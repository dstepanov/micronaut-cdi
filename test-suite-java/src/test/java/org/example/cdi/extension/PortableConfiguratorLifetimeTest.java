package org.example.cdi.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configurator a {@code ProcessAnnotatedType} hands out is only to be used while that event is notified
 * (section 11.5.6): an extension that keeps it and uses it during a later event is refused, rather than have what it
 * added accepted and ignored.
 */
class PortableConfiguratorLifetimeTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Late {
    }

    /**
     * The class the configurator configures.
     */
    @ApplicationScoped
    public static class Kettle {
    }

    public static class Keeping implements Extension {
        AnnotatedTypeConfigurator<Kettle> kept;

        void type(@Observes ProcessAnnotatedType<Kettle> event) {
            kept = event.configureAnnotatedType();
        }

        void after(@Observes AfterBeanDiscovery event) {
            kept.add(new AnnotationLiteral<Late>() { });
        }
    }

    @Test
    void aConfiguratorUsedAfterItsEventIsRefused() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> SeContainerInitializer.newInstance()
            .disableDiscovery().addBeanClasses(Kettle.class).addExtensions(new Keeping()).initialize().close());
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("only to be used while its observers are notified"),
            messages.toString());
    }
}
