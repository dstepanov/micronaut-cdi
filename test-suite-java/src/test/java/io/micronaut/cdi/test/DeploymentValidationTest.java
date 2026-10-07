package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container validates a deployment as it starts, whether it was started through the SE bootstrap or as an
 * application context, whether or not anything ever asks for the bean that has the problem: an unsatisfied or
 * ambiguous dependency, at a field or a parameter of an observer or a disposer method alike (CDI 4.1 section
 * 5.2.2), an injection point that resolves to a bean in a normal scope that cannot be proxied (sections 3.10 and
 * 5.4), and two beans of one name or a name that is the path prefix of another (section 5.3.1). Optional and collection injection require a bean of the full declared type.
 * Each deployment is compiled in memory, since a class compiled with this suite would be in the deployment of
 * every test.
 */
class DeploymentValidationTest {

    @Test
    void anInjectionPointThatResolvesToAnUnproxyableNormalScopedBeanIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Inject
                Final injected;

                @jakarta.enterprise.context.ApplicationScoped
                public static class Final {
                    public final void x() {
                    }
                }
            }
            """, "resolves to a bean that cannot be proxied");
    }

    @Test
    void anInjectionPointThatResolvesToASealedNormalScopedBeanIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Inject
                Sealed injected;

                @jakarta.enterprise.context.ApplicationScoped
                public static sealed class Sealed permits Permitted {
                }

                @jakarta.enterprise.inject.Vetoed
                public static final class Permitted extends Sealed {
                }
            }
            """, "resolves to a bean that cannot be proxied");
    }

    @Test
    void anUnsatisfiedInjectionPointIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Inject
                Runnable missing;
            }
            """, "has no bean to satisfy it");
    }

    @Test
    void anAmbiguousInjectionPointIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Inject
                Runnable ambiguous;

                @jakarta.enterprise.context.Dependent
                public static class A implements Runnable {
                    public void run() {
                    }
                }

                @jakarta.enterprise.context.Dependent
                public static class B implements Runnable {
                    public void run() {
                    }
                }
            }
            """, "is ambiguous");
    }

    @Test
    void anUnsatisfiedObserverParameterIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                void hear(@jakarta.enterprise.event.Observes Consumer event, Runnable missing) {
                }
            }
            """, "has no bean to satisfy it");
    }

    @Test
    void anUnsatisfiedDisposerParameterIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.enterprise.inject.Produces
                @jakarta.inject.Named("invalid.produced")
                StringBuilder value() {
                    return new StringBuilder();
                }

                void dispose(@jakarta.enterprise.inject.Disposes @jakarta.inject.Named("invalid.produced")
                             StringBuilder value, Runnable missing) {
                }
            }
            """, "has no bean to satisfy it");
    }

    @Test
    void twoBeansOfOneNameAreADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            @jakarta.inject.Named("invalid.same")
            public class Consumer {

                @jakarta.enterprise.context.Dependent
                @jakarta.inject.Named("invalid.same")
                public static class Other {
                }
            }
            """, "resolves to more than one bean");
    }

    @Test
    void aNameThatIsThePathPrefixOfAnotherIsADeploymentProblem() {
        assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            @jakarta.inject.Named("invalid.same")
            public class Consumer {

                @jakarta.enterprise.context.Dependent
                @jakarta.inject.Named("invalid.same.child")
                public static class Other {
                }
            }
            """, "is a path prefix of the name invalid.same.child");
    }

    @Test
    void anUnproxyableNormalScopedBeanNothingIsInjectedWithDeploys() {
        try (ApplicationContext context = InMemoryDeployment.start("valid.Consumer", """
            package valid;

            @jakarta.enterprise.context.ApplicationScoped
            public class Consumer {
                public final void x() {
                }
            }
            """)) {
            assertTrue(context.isRunning());
        }
    }

    @Test
    void missingOptionalAndCollectionProducersAreDeploymentProblems() {
        for (String type : java.util.List.of("java.util.Optional<Element>", "java.util.List<Element>",
            "java.util.Set<Element>", "java.util.Collection<Element>", "java.util.Map<String, Element>",
            "java.util.stream.Stream<Element>", "java.lang.Iterable<Element>")) {
            assertRejected("""
                package invalid;
                @jakarta.enterprise.context.Dependent
                public class Consumer {
                    @jakarta.inject.Inject %s missing;
                    public interface Element {}
                }
                """.formatted(type), "has no bean to satisfy it");
        }
    }

    @Test
    void aUserFieldStartingWithDollarStillRequiresAContainerProducer() {
        assertRejected("""
            package invalid;
            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Inject java.util.Optional<String> $missing;
            }
            """, "has no bean to satisfy it");
    }

    @Test
    void ambiguousOptionalAndCollectionProducersAreDeploymentProblems() {
        for (String type : java.util.List.of("java.util.Optional<String>", "java.util.List<String>", "java.util.Set<String>",
            "java.util.Map<String, String>", "java.util.stream.Stream<String>", "java.lang.Iterable<String>")) {
            assertRejected("""
                package invalid;
                @jakarta.enterprise.context.Dependent
                public class Consumer {
                    @jakarta.inject.Inject %s ambiguous;
                    @jakarta.enterprise.inject.Produces %s first() { return null; }
                    @jakarta.enterprise.inject.Produces %s second() { return null; }
                }
                """.formatted(type, type, type), "is ambiguous");
        }
    }

    static void assertRejected(String source, String expected) {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> InMemoryDeployment.start("invalid.Consumer", source).close(), source);
        DeploymentException problem = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeploymentException deployment) {
                problem = deployment;
                break;
            }
        }
        assertNotNull(problem, () -> "expected a deployment problem, got " + failure);
        assertTrue(problem.getMessage().contains(expected), problem.getMessage());
    }
}
