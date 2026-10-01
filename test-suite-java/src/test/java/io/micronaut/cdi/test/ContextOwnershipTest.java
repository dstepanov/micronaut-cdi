package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * An instance has one owner that destroys it. Asking a context for the instance of a bean of the container hands
 * out the one its scope holds without taking it over, so it is destroyed once, with its scope. Destroying a bean
 * through its context destroys the instance the context holds - the one of a singleton too - and the next one asked
 * for is a new one.
 */
class ContextOwnershipTest {

    static final AtomicInteger DESTROYED = new AtomicInteger();

    @ApplicationScoped
    public static class OwnedScoped {
        public void touch() {
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @RequestScoped
    public static class OwnedRequest {
        public void touch() {
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Singleton
    public static class OwnedSingle {
        public void touch() {
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanManager manager, Class<T> type) {
        return (Bean<T>) manager.resolve(manager.getBeans(type));
    }

    @BeforeEach
    void reset() {
        DESTROYED.set(0);
    }

    @Test
    void askingTheApplicationContextForAnInstanceItHoldsDoesNotDestroyItTwice() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            context.getBean(OwnedScoped.class).touch();
            Bean<OwnedScoped> bean = beanOf(manager, OwnedScoped.class);
            Context applicationContext = manager.getContext(ApplicationScoped.class);

            OwnedScoped held = applicationContext.get(bean);
            assertSame(held, applicationContext.get(bean, manager.createCreationalContext(bean)));
            assertSame(held, applicationContext.get(bean));
        }
        assertEquals(1, DESTROYED.get());
    }

    @Test
    void askingTheRequestContextForAnInstanceItHoldsDoesNotDestroyItTwice() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            context.getBean(RequestScope.class).run(() -> {
                context.getBean(OwnedRequest.class).touch();
                Bean<OwnedRequest> bean = beanOf(manager, OwnedRequest.class);
                Context requestContext = manager.getContext(RequestScoped.class);

                OwnedRequest held = requestContext.get(bean);
                assertSame(held, requestContext.get(bean, manager.createCreationalContext(bean)));
            });
            assertEquals(1, DESTROYED.get());
        }
    }

    @Test
    void destroyingABeanThroughTheApplicationContextDestroysItOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<OwnedScoped> bean = beanOf(manager, OwnedScoped.class);
            AlterableContext applicationContext = (AlterableContext) manager.getContext(ApplicationScoped.class);
            OwnedScoped first = applicationContext.get(bean, manager.createCreationalContext(bean));

            applicationContext.destroy(bean);

            assertEquals(1, DESTROYED.get());
            assertNotSame(first, applicationContext.get(bean, manager.createCreationalContext(bean)));
        }
        assertEquals(2, DESTROYED.get());
    }

    @Test
    void destroyingASingletonThroughItsContextDestroysIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            OwnedSingle first = context.getBean(OwnedSingle.class);
            Bean<OwnedSingle> bean = beanOf(manager, OwnedSingle.class);

            ((AlterableContext) manager.getContext(Singleton.class)).destroy(bean);

            assertEquals(1, DESTROYED.get());
            assertNotSame(first, context.getBean(OwnedSingle.class));
        }
        assertEquals(2, DESTROYED.get());
    }
}
