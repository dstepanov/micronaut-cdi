package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Section 2.5.1 without the reflection module: {@code Contextual.create()} creates a new instance each time, and
 * the context is what creates the instance of the scope once.
 */
class BeanCreateWithoutReflectionTest {

    @ApplicationScoped
    static class NoReflectionScopedItem {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        int ping() {
            return 1;
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatingABeanCreatesANewInstanceAndTheContextHoldsOne() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            Bean<NoReflectionScopedItem> bean = (Bean<NoReflectionScopedItem>) container.resolve(
                container.getBeans(NoReflectionScopedItem.class));
            CreationalContext<NoReflectionScopedItem> first = container.createCreationalContext(bean);
            Context applicationContext = container.getContext(ApplicationScoped.class);
            NoReflectionScopedItem.DESTROYED.set(0);

            NoReflectionScopedItem created = bean.create(first);
            NoReflectionScopedItem again = bean.create(container.createCreationalContext(bean));
            NoReflectionScopedItem held = applicationContext.get(bean, container.createCreationalContext(bean));

            assertNotSame(created, again);
            assertNotSame(created, held);
            assertSame(held, applicationContext.get(bean));
            assertEquals(1, created.ping());

            bean.destroy(created, first);
            assertEquals(1, NoReflectionScopedItem.DESTROYED.get());
        }
    }
}
