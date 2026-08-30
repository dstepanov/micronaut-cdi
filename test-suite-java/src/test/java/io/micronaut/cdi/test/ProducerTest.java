package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducerTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Legacy {
    }

    record Connection(String name) {
    }

    static class Config {
        final String url;

        Config(String url) {
            this.url = url;
        }
    }

    @ApplicationScoped
    static class Producers {

        static final List<String> DISPOSED = new ArrayList<>();

        @Produces
        @Dependent
        Connection connection() {
            return new Connection("primary");
        }

        @Produces
        @Legacy
        Connection legacyConnection() {
            return new Connection("legacy");
        }

        @Produces
        Config config = new Config("jdbc:h2:mem:test");

        void close(@Disposes Connection connection) {
            DISPOSED.add(connection.name());
        }
    }

    @Singleton
    static class Uses {
        @Inject
        Connection connection;
        @Inject
        Config config;
        @Inject
        @Legacy
        Connection legacy;
    }

    @Test
    void producerMethodsAndFieldsBecomeBeans() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Uses uses = context.getBean(Uses.class);
            assertEquals("primary", uses.connection.name());
            assertEquals("legacy", uses.legacy.name());
            assertEquals("jdbc:h2:mem:test", uses.config.url);
        }
    }

    @Test
    void anUnqualifiedInjectionPointResolvesToTheDefaultProducer() {
        try (ApplicationContext context = ApplicationContext.run()) {
            // both producers are candidates for an injection point that names no qualifier. The one that
            // declares no qualifier of its own is the one with the default qualifier, and is the one resolved
            assertEquals("primary", context.getBean(Connection.class).name());
        }
    }

    @Test
    void theDisposerIsInvokedForTheProducerItMatches() {
        Producers.DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("primary", context.getBean(Uses.class).connection.name());
        }
        assertTrue(Producers.DISPOSED.contains("primary"),
            "the disposer should have been invoked for the produced connection, got " + Producers.DISPOSED);
        // the disposed parameter declares no qualifier, so it has the default one, and the disposer disposes of
        // what the default producer produced rather than of every connection
        assertFalse(Producers.DISPOSED.contains("legacy"),
            "the qualified connection has no disposer, got " + Producers.DISPOSED);
    }
}
