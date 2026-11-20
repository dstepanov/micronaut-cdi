package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The request context is active during the {@code @PostConstruct} callback of any bean (CDI 4.1 section 6.7.1),
 * a dependent bean created through a programmatic lookup on a thread with no request among them.
 */
class DependentLookupRequestContextTest {

    @Test
    void aDependentBeanALookupCreatesHasARequestInItsPostConstruct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(BeanManager.class).createInstance();
            assertEquals("ticket", lookup.select(Worker.class).get().seen);
            assertEquals("ticket", lookup.select(Worker.class).getHandle().get().seen);
        }
    }

    /**
     * A request scoped bean.
     */
    @RequestScoped
    public static class Ticket {
        public String name() {
            return "ticket";
        }
    }

    /**
     * A dependent bean that calls the request scoped bean as it is constructed.
     */
    @Dependent
    public static class Worker {

        @Inject
        Ticket ticket;

        String seen;

        @PostConstruct
        void constructed() {
            seen = ticket.name();
        }
    }
}
