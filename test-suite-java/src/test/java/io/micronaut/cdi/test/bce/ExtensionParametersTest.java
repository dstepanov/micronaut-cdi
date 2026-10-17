package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections 12.1 to 12.4 hand an extension method each parameter its phase declares. Three that
 * validation accepts are not supplied: {@code Messages} to a discovery method, {@code Types} to an enhancement method,
 * and {@code InvokerFactory} to an observer registration method. Compiled in memory, with the extension handed to
 * the processor through the hook the TCK harness uses.
 */
class ExtensionParametersTest {

    private static final String SOURCE = """
        package bce;

        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;

        @ApplicationScoped
        public class Subject {
            void on(@Observes String event) {
            }
        }
        """;

    public static final class DiscoveryMessages implements BuildCompatibleExtension {
        static volatile boolean invoked;
        static volatile Messages messages;

        @Discovery
        public void discover(Messages messages) {
            invoked = true;
            DiscoveryMessages.messages = messages;
        }
    }

    public static final class EnhancementTypes implements BuildCompatibleExtension {
        static volatile Types types;

        @Enhancement(types = Object.class, withSubtypes = true)
        public void enhance(ClassConfig config, Types types) {
            EnhancementTypes.types = types;
        }
    }

    public static final class ObserverInvokerFactory implements BuildCompatibleExtension {
        static volatile InvokerFactory invokers;

        @Registration(types = Object.class)
        public void register(ObserverInfo observer, InvokerFactory invokers) {
            ObserverInvokerFactory.invokers = invokers;
        }
    }

    @Test
    void aDiscoveryMethodIsHandedMessages() {
        DiscoveryMessages.invoked = false;
        compile(DiscoveryMessages.class);

        assertTrue(DiscoveryMessages.invoked, "the discovery method ran");
        assertNotNull(DiscoveryMessages.messages, "the discovery method is handed a Messages");
    }

    @Test
    void anEnhancementMethodIsHandedTypes() {
        EnhancementTypes.types = null;
        assertDoesNotThrow(() -> compile(EnhancementTypes.class));

        assertNotNull(EnhancementTypes.types, "the enhancement method is handed a Types");
    }

    @Test
    void anObserverRegistrationMethodIsHandedAnInvokerFactory() {
        ObserverInvokerFactory.invokers = null;
        assertDoesNotThrow(() -> compile(ObserverInvokerFactory.class));

        assertNotNull(ObserverInvokerFactory.invokers, "the registration method is handed an InvokerFactory");
    }

    private static void compile(Class<? extends BuildCompatibleExtension> extension) {
        try (JavaParser parser = ExtensionCompilation.of(extension)) {
            parser.generate("bce.Subject", SOURCE);
        }
    }
}
