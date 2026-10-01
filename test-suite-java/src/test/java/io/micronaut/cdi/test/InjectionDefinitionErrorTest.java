package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injection points the specification makes definition errors, refused as the class compiles rather than left to
 * be injected some other way, or not at all.
 */
class InjectionDefinitionErrorTest {

    @Test
    void aFinalInjectedFieldIsRefused() {
        // CDI 4.1 section 3.6: an injected field is a non-static, non-final field; one written final would be silently
        // left as it was initialized
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.spi.BeanManager;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                final BeanManager manager = null;
            }
            """, "An injected field may not be final");
    }

    @Test
    void anInjectionPointOfATypeVariableOfAGenericBeanIsRefused() {
        // CDI 4.1 section 5.2.3: an injection point whose type is a type variable is a definition error. A
        // generic bean class has nothing to resolve its variable: only an abstract class's is resolved, by the
        // bean that extends it
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject<T> {
                @Inject
                T value;
            }
            """, "An injection point whose declared type is a type variable is a definition error");
    }

    static void assertRefused(String source, String expected) {
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
