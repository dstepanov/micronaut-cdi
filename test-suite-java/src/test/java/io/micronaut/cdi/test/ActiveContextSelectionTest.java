package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiContext;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which context of a scope is the active one: none, one, or more than one active, and a scope no context is
 * registered for.
 */
class ActiveContextSelectionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Unregistered {
    }

    @Test
    void noneOfTheContextsIsActive() {
        assertNull(CdiContext.activeAmong(List.of(new Fixed(false), new Fixed(false)), RequestScoped.class));
    }

    @Test
    void theOneActiveContextIsSelected() {
        Fixed active = new Fixed(true);
        assertSame(active, CdiContext.activeAmong(List.of(new Fixed(false), active), RequestScoped.class));
    }

    @Test
    void twoActiveContextsAreRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> CdiContext.activeAmong(List.of(new Fixed(true), new Fixed(true)), RequestScoped.class));
        assertEquals("More than one context of " + RequestScoped.class.getName() + " is active on the current thread",
            e.getMessage());
    }

    @Test
    void theContainerTellsAnUnknownScopeFromAnInactiveOne() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            assertTrue(container.getContexts(Unregistered.class).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> container.getContext(Unregistered.class));
            assertThrows(ContextNotActiveException.class, () -> container.getContext(RequestScoped.class));
            assertTrue(container.getContext(ApplicationScoped.class).isActive());
        }
    }

    private record Fixed(boolean active) implements Context {
        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(Contextual<T> contextual) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
