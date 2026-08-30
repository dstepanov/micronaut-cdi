package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The behaviour of Micronaut itself that this module runs into, written the way a plain Micronaut application
 * would write it, so that it is not mistaken for something this module does.
 *
 * <p>It is disabled rather than left out: a disabled test that says what is wrong is what will fail the moment it
 * is fixed, and it is the smallest reproduction of a difference recorded under Conformance.</p>
 */
class MicronautCoreIssuesTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {
    }

    record Thing(String name) {
    }

    @Factory
    @Singleton
    @Primary
    @Marker
    static class PrimaryAndQualifiedFactory {
        @Bean
        @Prototype
        Thing thing() {
            return new Thing("made");
        }
    }

    @Test
    void aFactoryThatIsPrimaryAndAlsoQualifiedCanProduceABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("made", context.getBean(Thing.class).name());
        }
    }
}
