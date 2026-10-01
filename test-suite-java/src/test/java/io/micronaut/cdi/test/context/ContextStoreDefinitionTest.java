package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.context.ApplicationScope;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The store CdiContext keeps inside a scope map is a CreatedBean whose
 * definition() throws, and Core's scope lookup by definition reads the definition of every entry it passes.
 */
class ContextStoreDefinitionTest {

    @ApplicationScoped
    public static class Held {
        public String id() {
            return "held";
        }
    }

    /** A contextual a program hands the application context, which puts the store into the scope map. */
    static final class Handed implements Contextual<String> {
        @Override
        public String create(CreationalContext<String> creationalContext) {
            return "handed";
        }

        @Override
        public void destroy(String instance, CreationalContext<String> creationalContext) {
        }
    }

    @Test
    void aLookupByDefinitionPassesTheStore() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Handed handed = new Handed();
            container.getContext(ApplicationScoped.class).get(handed, container.createCreationalContext(handed));
            BeanDefinition<Held> definition = context.getBeanDefinition(Held.class);

            assertDoesNotThrow(() -> context.getBean(ApplicationScope.class).findBeanRegistration(definition));
        }
    }

    @Test
    void destroyingAScopedProxyPassesTheStore() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Held held = context.getBean(Held.class);
            held.id();
            Handed handed = new Handed();
            container.getContext(ApplicationScoped.class).get(handed, container.createCreationalContext(handed));

            assertDoesNotThrow(() -> context.destroyBean(held));
        }
    }
}
