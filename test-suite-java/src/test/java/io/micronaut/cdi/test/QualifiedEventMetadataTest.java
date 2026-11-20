package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The built-in event metadata is the bean of the type {@code EventMetadata} and the default qualifier (CDI 4.1
 * section 9.4.3): an injection point of the type with a qualifier of the application's is an ordinary one, which a
 * producer of the application satisfies, in a bean and at an observer method's parameter alike.
 */
class QualifiedEventMetadataTest {

    @Test
    void aQualifiedEventMetadataIsTheApplicationsBean() {
        Listener.received = null;
        try (ApplicationContext context = ApplicationContext.run()) {
            assertSame(Audit.METADATA, context.getBean(BeanManager.class).createInstance()
                .select(Holder.class).get().metadata);
            context.getBean(BeanManager.class).getEvent().select(Bell.class).fire(new Bell());
            assertSame(Audit.METADATA, Listener.received);
        }
    }

    /**
     * The application's qualifier.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audit {

        /**
         * The application's metadata.
         */
        EventMetadata METADATA = new EventMetadata() {
            @Override
            public Set<Annotation> getQualifiers() {
                return Set.of();
            }

            @Override
            public InjectionPoint getInjectionPoint() {
                return null;
            }

            @Override
            public Type getType() {
                return Object.class;
            }
        };
    }

    /**
     * The event.
     */
    public static final class Bell {
    }

    /**
     * Produces the application's metadata.
     */
    @Dependent
    public static class Producer {
        @Produces
        @Audit
        EventMetadata audit() {
            return Audit.METADATA;
        }
    }

    /**
     * A bean injected with the application's metadata.
     */
    @Dependent
    public static class Holder {
        @Inject
        @Audit
        EventMetadata metadata;
    }

    /**
     * An observer handed the application's metadata.
     */
    @Dependent
    public static class Listener {
        static volatile EventMetadata received;

        void on(@Observes Bell bell, @Audit EventMetadata metadata) {
            received = metadata;
        }
    }
}
