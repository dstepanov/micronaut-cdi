package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subclass that declares no bean defining annotation is not discovered by the scope it inherits (section 2.5.1),
 * and one that declares what only a bean would - an alternative of its superclass, the mock of a test - is warned
 * about as it compiles rather than left out without a word.
 */
class UndiscoveredSubclassWarningTest {

    @Test
    void anUndiscoveredAlternativeIsWarnedAbout() {
        try (JavaParser parser = new JavaParser()) {
            parser.generate("undiscovered.Real", """
                package undiscovered;

                import jakarta.annotation.Priority;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.enterprise.inject.Alternative;

                @ApplicationScoped
                public class Real {

                    @Alternative
                    @Priority(1)
                    public static class Mock extends Real {
                    }

                    public static class Plain extends Real {
                    }
                }
                """);
            List<String> warnings = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.WARNING
                    || diagnostic.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(diagnostic -> diagnostic.getMessage(null))
                .filter(message -> message.contains("is not discovered as a bean"))
                .toList();
            assertEquals(1, warnings.size(), String.valueOf(warnings));
            assertTrue(warnings.get(0).contains("Real$Mock") && warnings.get(0).contains("@Alternative"),
                warnings.get(0));
        }
    }
}
