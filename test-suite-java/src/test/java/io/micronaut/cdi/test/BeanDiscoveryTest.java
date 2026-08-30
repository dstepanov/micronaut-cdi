package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Vetoed;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanDiscoveryTest {

    interface Greeting {
        String text();
    }

    @ApplicationScoped
    static class PlainGreeting implements Greeting {
        @Override
        public String text() {
            return "hello";
        }
    }

    @ApplicationScoped
    @Alternative
    @Priority(100)
    static class SelectedGreeting implements Greeting {
        @Override
        public String text() {
            return "selected";
        }
    }

    interface Farewell {
        String text();
    }

    @ApplicationScoped
    static class PlainFarewell implements Farewell {
        @Override
        public String text() {
            return "bye";
        }
    }

    @ApplicationScoped
    @Alternative
    static class UnselectedFarewell implements Farewell {
        @Override
        public String text() {
            return "never";
        }
    }

    @Dependent
    @Vetoed
    static class NotABean {
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void aVetoedClassIsNotABean() {
        assertTrue(context.findBean(NotABean.class).isEmpty(),
            "a class annotated Vetoed should not be a bean");
    }

    @Test
    void anAlternativeThatDeclaresNoPriorityIsNotEnabled() {
        assertTrue(context.getBeanDefinitions(Farewell.class).stream()
                .noneMatch(d -> d.getBeanType().getSimpleName().contains("UnselectedFarewell")),
            "an alternative with no priority is not selected, so it is not a bean");
        assertEquals("bye", context.getBean(Farewell.class).text());
    }

    @Test
    void anAlternativeWithAPriorityReplacesTheBeanItIsAnAlternativeTo() {
        Greeting greeting = context.getBean(Greeting.class);
        assertEquals("selected", greeting.text());
        assertInstanceOf(SelectedGreeting.class, greeting);
    }
}
