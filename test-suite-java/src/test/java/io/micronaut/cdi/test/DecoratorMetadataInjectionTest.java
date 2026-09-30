package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 9.4 of CDI Full makes the metadata of a decorator — {@code Decorator<X>} and the decorated
 * {@code Bean<X>} — something only a decorator may be told, and asking for it anywhere else a definition error.
 * Decorators are not implemented here, so nothing may ask: each of these is refused as it compiles rather than
 * left to fail as an unsatisfied dependency. Compiled in memory, the way the kit's broken deployments are.
 */
class DecoratorMetadataInjectionTest {

    @Test
    void decoratorMetadataInjectedIntoABeanIsRefused() {
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.spi.Decorator;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                Decorator<Subject> decorator;
            }
            """, "Decorator metadata reaches only a decorator");
    }

    @Test
    void theDecoratedBeanInjectedIntoAFieldIsRefused() {
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Decorated;
            import jakarta.enterprise.inject.spi.Bean;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                @Decorated
                Bean<Subject> bean;
            }
            """, "The decorated Bean is the metadata of the bean a decorator wraps");
    }

    @Test
    void theDecoratedBeanInjectedIntoAConstructorIsRefused() {
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Decorated;
            import jakarta.enterprise.inject.spi.Bean;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                public Subject(@Decorated Bean<Subject> bean) {
                }
            }
            """, "The decorated Bean is the metadata of the bean a decorator wraps");
    }

    @Test
    void theDecoratedBeanInjectedIntoAnInitializerIsRefused() {
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Decorated;
            import jakarta.enterprise.inject.spi.Bean;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                public void initialize(@Decorated Bean<Subject> bean) {
                }
            }
            """, "The decorated Bean is the metadata of the bean a decorator wraps");
    }

    private static void assertRefused(String source, String expected) {
        try (JavaParser parser = new JavaParser()) {
            assertThrows(RuntimeException.class, () -> parser.generate("broken.Subject", source),
                "the class is refused as it compiles");
            String errors = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
            assertTrue(errors.contains(expected), "expected a definition error, got: " + errors);
        }
    }
}
