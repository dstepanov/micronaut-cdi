package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class that declares the dependent pseudo-scope over the normal scope it would inherit is a dependent bean
 * (section 2.3.1): the rules of a normal scope - no public field, a proxyable class - are not its rules.
 */
class DependentSubclassOfNormalScopeTest {

    @Test
    void aDependentSubclassOfANormalScopedClassMayDeclareAPublicField() {
        try (JavaParser parser = new JavaParser()) {
            parser.generate("accepted.Subject", """
                package accepted;

                import jakarta.enterprise.context.Dependent;
                import jakarta.enterprise.context.NormalScope;

                import java.lang.annotation.Inherited;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class Subject {

                    @NormalScope
                    @Inherited
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Cloudy {
                    }

                    @Cloudy
                    public static class Mist {
                    }

                    @Dependent
                    public static class Fog extends Mist {
                        public int calls;
                    }
                }
                """);
            String errors = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
            assertTrue(errors.isEmpty(), errors);
        }
    }
}
