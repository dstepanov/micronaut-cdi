package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.repeatable.Start;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The qualifiers of a bean and of an injection point written twice are reported, both of them. Not a
 * reproducer: it passes with and without CdiQualifiers' repeatable fallback, which is what says the fallback is
 * redundant for compiled metadata.
 */
class RepeatedQualifiersReportedTest {

    @Dependent
    @Start("A")
    @Start("B")
    public static class TwiceStarted {
    }

    @Dependent
    public static class Consumer {

        @Inject
        @Start("C")
        @Start("D")
        OnceStartedLookup lookup;
    }

    @Dependent
    @Start("C")
    @Start("D")
    public static class OnceStartedLookup {

        @Inject
        InjectionPoint injectionPoint;
    }

    @Test
    void aBeanReportsBothOccurrences() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.getBeans(TwiceStarted.class, Any.Literal.INSTANCE).iterator().next();
            Set<Annotation> qualifiers = bean.getQualifiers();

            assertTrue(qualifiers.contains(new Start.Literal("A")) && qualifiers.contains(new Start.Literal("B")),
                qualifiers.toString());
        }
    }

    @Test
    void anInjectionPointReportsBothOccurrences() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Set<Annotation> qualifiers = context.getBean(Consumer.class).lookup.injectionPoint.getQualifiers();

            assertTrue(qualifiers.contains(new Start.Literal("C")) && qualifiers.contains(new Start.Literal("D")),
                qualifiers.toString());
        }
    }
}
