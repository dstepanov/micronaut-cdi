package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean a Micronaut factory method produces is a bean of the type the method declares, with the arguments it was
 * written with, as a producer's is: not of the open declaration of the class it returns.
 */
class FactoryProductTypesTest {

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
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<jakarta.enterprise.inject.spi.Bean<?>> lists =
                container.getBeans(new TypeLiteral<List<Bolt>>() { }.getType());
            assertEquals(1, lists.size(), lists.toString());
            jakarta.enterprise.inject.spi.Bean<?> bolts = lists.iterator().next();
            assertTrue(container.getBeans(new TypeLiteral<Collection<Bolt>>() { }.getType()).contains(bolts),
                "a List<Bolt> is a Collection<Bolt>");
            assertTrue(container.getBeans(new TypeLiteral<Collection<Long>>() { }.getType()).stream()
                .noneMatch(bean -> bean.equals(bolts)), "and no Collection<Long>");
        }
    }
}
