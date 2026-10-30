package org.example.cdi.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.WithAnnotations;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An observer of {@code ProcessAnnotatedType} restricted by {@code @WithAnnotations} (section 11.5.6) is refused with
 * a message saying so, rather than notified of every class as if it were not restricted.
 */
class PortableWithAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {
    }

    /**
     * A class without the annotation the observer restricts itself to.
     */
    @ApplicationScoped
    public static class Unmarked {
    }

    public static class Restricted implements Extension {
        int notified;

        void type(@Observes @WithAnnotations(Marked.class) ProcessAnnotatedType<?> event) {
            notified++;
        }
    }

    @Test
    void anObserverRestrictedByAnnotationsIsRefused() {
        Restricted extension = new Restricted();
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
            () -> SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(Unmarked.class)
                .addExtensions(extension).initialize().close());
        assertTrue(failure.getMessage().contains("WithAnnotations"), failure.getMessage());
    }
}
