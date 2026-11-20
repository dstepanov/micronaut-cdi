package org.example.cdi.extension;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An observer of {@code ProcessAnnotatedType} sees the annotated type as the observers before it left it (CDI 4.1
 * section 11.5.6): a qualifier an earlier observer added with {@code configureAnnotatedType()} is present on the
 * type a later observer reads.
 */
class PortableAddedQualifierVisibleTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Swift {
    }

    static final class SwiftLiteral extends AnnotationLiteral<Swift> implements Swift {
    }

    static final Swift SWIFT = new SwiftLiteral();

    @Dependent
    public static class Courier {
    }

    static class Ordered implements Extension {

        volatile boolean seen;

        void first(@Observes @Priority(1) ProcessAnnotatedType<Courier> event) {
            event.configureAnnotatedType().add(SWIFT);
        }

        void second(@Observes @Priority(2) ProcessAnnotatedType<Courier> event) {
            seen = event.getAnnotatedType().isAnnotationPresent(Swift.class)
                && event.getAnnotatedType().getAnnotation(Swift.class) != null
                && event.getAnnotatedType().getAnnotations().stream()
                    .anyMatch(annotation -> annotation.annotationType() == Swift.class);
        }
    }

    @Test
    void aLaterObserverSeesTheQualifierAnEarlierOneAdded() {
        Ordered extension = new Ordered();
        try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Courier.class).addExtensions(extension).initialize()) {
            assertTrue(extension.seen);
        }
    }
}
