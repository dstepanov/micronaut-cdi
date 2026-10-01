package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container validates a deployment as it starts, whether it was started through the SE bootstrap or as an
 * application context: an injection point that resolves to a bean in a normal scope that cannot be proxied is a
 * deployment problem (CDI 4.1 sections 3.10 and 5.4), whether or not anything ever asks for the bean that has it.
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

    static void assertRejected(String source, String expected) {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> InMemoryDeployment.start("invalid.Consumer", source).close());
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
