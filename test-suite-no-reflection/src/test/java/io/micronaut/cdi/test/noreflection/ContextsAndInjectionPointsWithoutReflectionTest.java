package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Without the reflection module: an instance a scope holds is destroyed once, by its scope, whether or not a
 * context was asked for it; a singleton destroyed through its context is destroyed; and a bean reports the
 * parameters of its constructor, or of the producer method that makes it, as its injection points.
 */
class ContextsAndInjectionPointsWithoutReflectionTest {

    static final AtomicInteger DESTROYED = new AtomicInteger();

    @ApplicationScoped
    static class NoReflectionOwnedScoped {
        void touch() {
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Singleton
    static class NoReflectionOwnedSingle {
        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Dependent
    static class NoReflectionPart {
    }

    @Dependent
    static class NoReflectionAssembled {
        @Inject
        NoReflectionAssembled(NoReflectionPart part) {
        }
    }

    record NoReflectionProduct(NoReflectionPart part) {
    }

    @Dependent
    static class NoReflectionFactory {
        @Produces
        NoReflectionProduct produce(NoReflectionPart part) {
            return new NoReflectionProduct(part);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanContainer container, Class<T> type) {
        return (Bean<T>) container.resolve(container.getBeans(type));
    }

    @Test
    void anInstanceAScopeHoldsIsDestroyedOnce() {
        DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            context.getBean(NoReflectionOwnedScoped.class).touch();
            Bean<NoReflectionOwnedScoped> bean = beanOf(container, NoReflectionOwnedScoped.class);
            var applicationContext = container.getContext(ApplicationScoped.class);

            assertSame(applicationContext.get(bean),
                applicationContext.get(bean, container.createCreationalContext(bean)));
        }
        assertEquals(1, DESTROYED.get());
    }

    @Test
    void aSingletonDestroyedThroughItsContextIsDestroyed() {
        DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            NoReflectionOwnedSingle first = context.getBean(NoReflectionOwnedSingle.class);

            ((AlterableContext) container.getContext(Singleton.class))
                .destroy(beanOf(container, NoReflectionOwnedSingle.class));

            assertEquals(1, DESTROYED.get());
            assertNotSame(first, context.getBean(NoReflectionOwnedSingle.class));
        }
    }

    @Test
    void aBeanReportsTheParametersItIsMadeWithAsItsInjectionPoints() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);

            assertEquals(1, beanOf(container, NoReflectionAssembled.class).getInjectionPoints().size());
            assertEquals(1, beanOf(container, NoReflectionProduct.class).getInjectionPoints().size());
        }
    }
}
