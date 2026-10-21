package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.type.Argument;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean a Micronaut factory method produces is a bean of the type the method declares, read from what Micronaut
 * recorded of the method, with no reflection module on the classpath.
 */
class FactoryProductTypesWithoutReflectionTest {

    public record Bolt(String name) {
    }

    @Factory
    static class Bolts {
        @Bean
        @Prototype
        List<Bolt> bolts() {
            return List.of(new Bolt("hex"));
        }
    }

    @Test
    void aFactoryProductIsABeanOfTheTypeItsMethodDeclares() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertFalse(context.findBean(CdiReflection.class).isPresent());
            MicronautBeanContainer container =
                (MicronautBeanContainer) jakarta.enterprise.inject.spi.CDI.current().getBeanContainer();
            Set<jakarta.enterprise.inject.spi.Bean<?>> lists = container.getBeans(Argument.listOf(Bolt.class));
            assertEquals(1, lists.size(), lists.toString());
            jakarta.enterprise.inject.spi.Bean<?> bolts = lists.iterator().next();
            assertTrue(container.getBeans(Argument.of(Collection.class, Bolt.class)).contains(bolts),
                "a List<Bolt> is a Collection<Bolt>");
            assertFalse(container.getBeans(Argument.of(Collection.class, Long.class)).contains(bolts),
                "and no Collection<Long>");
        }
    }
}
