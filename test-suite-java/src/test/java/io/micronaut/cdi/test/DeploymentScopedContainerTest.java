package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container holding only the beans of one deployment.
 *
 * <p>The technology compatibility kit gives each of its test classes a deployment of its own — an archive of the
 * classes that test is about — and asserts against a container holding those and nothing else. Everything here is
 * compiled into one classpath instead, so a container that is one deployment has to be a container narrowed to
 * the classes the deployment names. Micronaut takes a predicate over the beans it will hold, which is what makes
 * that possible, and this is the proof of it.</p>
 */
class DeploymentScopedContainerTest {

    @ApplicationScoped
    static class InTheArchive {
        String name() {
            return "in";
        }
    }

    @ApplicationScoped
    static class NotInTheArchive {
        String name() {
            return "out";
        }
    }

    /**
     * A container of exactly the given classes, which is what a deployment of the kit amounts to here.
     */
    private static ApplicationContext deploymentOf(Class<?>... classes) {
        Set<String> archive = Set.of(java.util.Arrays.stream(classes).map(Class::getName).toArray(String[]::new));
        String scenarios = DeploymentScopedContainerTest.class.getName();
        return ApplicationContext.builder()
            .beansPredicate(bean -> {
                // a bean in a normal scope is held by the container as its client proxy, whose type is a
                // generated subclass; what the deployment names is the class that was written
                Class<?> type = bean instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy
                    ? proxy.getTargetType() : bean.getBeanType();
                return !type.getName().startsWith(scenarios) || archive.contains(type.getName());
            })
            .build()
            .start();
    }

    @Test
    void aContainerHoldsOnlyTheBeansOfItsDeployment() {
        try (ApplicationContext context = deploymentOf(InTheArchive.class)) {
            // the manager of this container, rather than whichever one started most recently
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals("in", context.getBean(InTheArchive.class).name());

            Set<Bean<?>> outside = manager.getBeans(NotInTheArchive.class);
            assertTrue(outside.isEmpty(), "a bean outside the deployment should not be in the container, got "
                + outside);
        }
    }

    @Test
    void anotherDeploymentHoldsTheOtherBean() {
        try (ApplicationContext context = deploymentOf(NotInTheArchive.class)) {
            assertEquals("out", context.getBean(NotInTheArchive.class).name());
            assertTrue(context.findBean(InTheArchive.class).isEmpty());
        }
    }
}
