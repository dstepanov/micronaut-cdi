package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A bean of Micronaut's own compiled and resolved beside the beans of the specification keeps Micronaut's rules:
 * adding this module to an application leaves the resolution of the application's own beans as it was.
 */
class MicronautBeanResolutionTest {

    public interface Engine {
    }

    @Bean
    @Secondary
    public static class Spare implements Engine {
    }

    @Bean
    @Named("v8")
    public static class V8 implements Engine {
    }

    /**
     * A {@code Named} parameter without a value is named by the parameter, as Micronaut has it; the specification
     * makes it a definition error, which is for its own beans alone.
     */
    @Bean
    public static class Car {
        final Engine engine;

        @Inject
        public Car(@Named Engine v8) {
            this.engine = v8;
        }
    }

    @Test
    void secondaryGivesWayAmongMicronautBeans() {
        try (ApplicationContext context = ApplicationContext.run()) {
            // neither bean is one of the specification: the default qualifier is not theirs to have, and the
            // secondary bean gives way to the other one, as it does without this module
            assertInstanceOf(V8.class, context.getBean(Engine.class));
        }
    }

    @Test
    void namedParameterWithoutValueIsNamedByTheParameter() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertInstanceOf(V8.class, context.getBean(Car.class).engine);
        }
    }
}
