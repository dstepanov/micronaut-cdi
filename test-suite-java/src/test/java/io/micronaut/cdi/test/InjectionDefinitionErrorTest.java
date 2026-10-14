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
    void aFinalInjectedFieldABeanInheritsIsRefused() {
        // Micronaut injects the points a bean inherits from a class that is no bean, so they are held to the rules
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.spi.BeanManager;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject extends Base {
            }

            abstract class Base {
                @Inject
                final BeanManager manager = null;
            }
            """, "An injected field may not be final");
    }

    @Test
    void aBeanDeclaringAScopeOfTheSpecificationAndOneOfItsOwnIsRefused() {
        // section 2.3.2: a bean declares at most one scope, whichever declares them
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.ApplicationScoped
            @Subject.Custom
            public class Subject {
                @jakarta.enterprise.context.NormalScope
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                public @interface Custom {
                }
            }
            """, "declares more than one scope");
    }

    @Test
    void aStaticInitializerMethodIsRefused() {
        // CDI 4.1 section 3.7.1: an initializer method is a non-abstract, non-static method; Micronaut would skip it
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.spi.BeanManager;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Inject
                static void initialize(BeanManager manager) {
                }
            }
            """, "An initializer method may not be static");
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

    @Test
    void aProducerMethodThatIsAnInitializerIsRefused() {
        // CDI 4.1 section 3.2.2: a producer method annotated Inject is a definition error
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            import jakarta.inject.Inject;

            @Dependent
            public class Subject {
                @Produces
                @Inject
                String value() {
                    return "produced";
                }
            }
            """, "A method may not be annotated both Inject and Produces");
    }

    @Test
    void aProducerOfACustomNormalScopeToldItsInjectionPointIsRefused() {
        // CDI 4.1 section 2.5.2.5: a bean of a normal scope cannot be told the injection point, and a producer's
        // scope is its own - here one a stereotype carries, of a normal scope the application declares
        assertRefused("""
            package broken;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            import jakarta.enterprise.inject.spi.InjectionPoint;

            @Dependent
            public class Subject {

                @jakarta.enterprise.context.NormalScope
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Busy {
                }

                @jakarta.enterprise.inject.Stereotype
                @Busy
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Busily {
                }

                public static class Thing {
                }

                @Produces
                @Busily
                Thing thing(InjectionPoint point) {
                    return new Thing();
                }
            }
            """, "A bean of a normal scope outlives any one injection point");
    }

    @Test
    void aProducerThatDeclaresTheDependentScopeBesideANormalStereotypeIsToldItsInjectionPoint() {
        // CDI 4.1 section 2.7.2: a scope the producer declares itself wins over the one a stereotype carries, so the
        // producer is dependent and may be told the injection point
        try (JavaParser parser = new JavaParser()) {
            parser.generate("accepted.Subject", """
                package accepted;

                import jakarta.enterprise.context.Dependent;
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;

                @Dependent
                public class Subject {

                    @jakarta.enterprise.inject.Stereotype
                    @jakarta.enterprise.context.RequestScoped
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface PerRequest {
                    }

                    public static class Thing {
                    }

                    @Produces
                    @Dependent
                    @PerRequest
                    Thing thing(InjectionPoint point) {
                        return new Thing();
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
