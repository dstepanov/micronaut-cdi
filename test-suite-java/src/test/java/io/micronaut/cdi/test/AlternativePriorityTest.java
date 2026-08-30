package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativePriorityTest {

    record Message(String text) {
    }

    @ApplicationScoped
    static class DefaultProducer {
        @Produces
        @Dependent
        Message message() {
            return new Message("default");
        }
    }

    @ApplicationScoped
    static class AlternativeProducer {
        @Produces
        @Dependent
        @Alternative
        @Priority(100)
        Message message() {
            return new Message("alternative");
        }
    }

    @Dependent
    static class Uses {
        @Inject
        Message message;
    }

    @Test
    void anAlternativeProducerWithAPriorityWinsInjection() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("alternative", context.getBean(Uses.class).message.text());
        }
    }

    @Test
    void getBeansReturnsBothAndResolvePicksTheAlternative() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(Message.class);
            assertEquals(2, beans.size(), "expected both producers, got " + beans);
            Bean<?> resolved = manager.resolve(beans);
            assertNotNull(resolved);
            assertTrue(resolved.isAlternative());
        }
    }

    @Test
    void debugDefinitions() {
        try (ApplicationContext context = ApplicationContext.run()) {
            for (io.micronaut.inject.BeanDefinition<Message> d : context.getBeanDefinitions(Message.class)) {
                System.out.println("DBG " + d.getName() + " order=" + d.getOrder()
                    + " primary=" + d.isPrimary()
                    + " ann=" + d.getAnnotationMetadata().getAnnotationNames());
            }
        }
    }
}
