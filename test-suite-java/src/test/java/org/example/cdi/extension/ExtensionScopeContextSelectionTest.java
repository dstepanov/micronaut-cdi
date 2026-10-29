package org.example.cdi.extension;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creating an instance of a scope that a portable extension registered several contexts for goes through the
 * Micronaut scope the container registers for those contexts ({@code ExtensionCustomScope}), not through the
 * container's {@code getContext}: the instance is created by the one context that is active, none active is a
 * context that is not active, and two active are rejected before either creates an instance.
 */
class ExtensionScopeContextSelectionTest {

    @RequestScoped
    public static class Ticket {
        public String number() {
            return "t-1";
        }
    }

    /** A context of the request scope that is active only when the test says so. */
    static class Counter implements AlterableContext {
        final Map<Contextual<?>, Object> held = new ConcurrentHashMap<>();
        volatile boolean active;

        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) held.computeIfAbsent(contextual, key -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) held.get(contextual);
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            held.remove(contextual);
        }
    }

    static class AddingCounters implements Extension {
        final Counter first = new Counter();
        final Counter second = new Counter();
        final Counter third = new Counter();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(first);
            event.addContext(second);
            event.addContext(third);
        }
    }

    @Test
    void theOneActiveContextAmongSeveralCreatesTheInstance() {
        AddingCounters adding = new AddingCounters();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Ticket.class).addExtensions(adding).initialize()) {
            Ticket ticket = container.select(Ticket.class).get();
            ContextNotActiveException inactive = assertThrows(ContextNotActiveException.class, ticket::number);
            assertTrue(inactive.getMessage().startsWith("No context of "), inactive.getMessage());

            adding.third.active = true;
            assertEquals("t-1", ticket.number());
            assertEquals(0, adding.first.held.size() + adding.second.held.size());
            assertEquals(1, adding.third.held.size());

            adding.first.active = true;
            IllegalStateException ambiguous = assertThrows(IllegalStateException.class, ticket::number);
            assertTrue(ambiguous.getMessage().startsWith("More than one context of "), ambiguous.getMessage());
            assertEquals(0, adding.first.held.size(), "the ambiguity is found before an instance is created");

            adding.third.active = false;
            assertEquals("t-1", ticket.number());
            assertEquals(1, adding.first.held.size());
            assertEquals(0, adding.second.held.size());
        }
    }
}
