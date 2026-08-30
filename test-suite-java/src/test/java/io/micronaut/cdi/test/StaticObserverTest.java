package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticObserverTest {

    static class Delivery { }

    @RequestScoped
    static class StaticObserver {
        static boolean received;

        public static void accept(@Observes Delivery delivery) {
            received = true;
        }
    }

    @Test
    void aStaticObserverIsNotifiedWithoutAContext() {
        StaticObserver.received = false;
        try (ApplicationContext context = ApplicationContext.run()) {
            jakarta.enterprise.inject.spi.BeanManager manager =
                context.getBean(jakarta.enterprise.inject.spi.BeanManager.class);
            manager.getEvent().select(Delivery.class).fire(new Delivery());
            assertTrue(StaticObserver.received, "the static observer needs no instance and no context");
        }
    }
}
