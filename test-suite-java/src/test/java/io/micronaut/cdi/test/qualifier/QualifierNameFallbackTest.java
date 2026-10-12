package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lookup selects a bean by the qualifiers the bean has, and by nothing else: a bean whose class name begins with
 * the simple name of a qualifier it does not carry is not selected by that qualifier, and a bean that carries a
 * qualifier is not selected by a lookup that names none (section 2.4.2). Micronaut's own annotation qualifiers fall
 * back to matching the simple name, which these lookups do not.
 */
class QualifierNameFallbackTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Special {
    }

    interface Greeter {
        String greet();
    }

    @Special
    @Dependent
    static class DefaultGreeter implements Greeter {
        @Override
        public String greet() {
            return "special";
        }
    }

    @Dependent
    static class SpecialGreeter implements Greeter {
        @Override
        public String greet() {
            return "default";
        }
    }

    @Test
    void aLookupThatNamesNoQualifierSelectsTheDefaultBeanOnly() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Greeter> greeters = context.getBean(MicronautBeanContainer.class).createInstance()
                .select(Greeter.class);
            assertEquals(List.of("default"), greeters.stream().map(Greeter::greet).toList());
            assertEquals("default", greeters.get().greet());
        }
    }

    @Test
    void aQualifierSelectsTheBeansThatCarryIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Greeter> special = context.getBean(MicronautBeanContainer.class).createInstance()
                .select(Greeter.class, new AnnotationLiteral<Special>() { });
            assertEquals(List.of("special"), special.stream().map(Greeter::greet).toList());
        }
    }
}
