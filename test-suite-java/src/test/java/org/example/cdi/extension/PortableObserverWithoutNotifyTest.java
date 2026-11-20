package org.example.cdi.extension;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An observer method a portable extension adds that implements neither {@code notify} method is a definition error
 * (CDI 4.1 section 11.5.3), reported as the container starts rather than at the first event.
 */
class PortableObserverWithoutNotifyTest {

    /**
     * The observed event.
     */
    public static final class Message {
    }

    static class Adding implements Extension {
        void after(@Observes AfterBeanDiscovery event) {
            event.addObserverMethod(new ObserverMethod<Message>() {
                @Override
                public Class<?> getBeanClass() {
                    return Adding.class;
                }

                @Override
                public Type getObservedType() {
                    return Message.class;
                }

                @Override
                public Set<Annotation> getObservedQualifiers() {
                    return Set.of();
                }

                @Override
                public Reception getReception() {
                    return Reception.ALWAYS;
                }

                @Override
                public TransactionPhase getTransactionPhase() {
                    return TransactionPhase.IN_PROGRESS;
                }
            });
        }
    }

    @Test
    void anObserverMethodWithoutNotifyIsADefinitionError() {
        assertThrows(DefinitionException.class, () -> {
            try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
                .addExtensions(new Adding()).initialize()) {
                // the start alone has to fail
            }
        });
    }
}
