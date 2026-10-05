package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Section 2.5.1: {@code Contextual.create()} creates a new contextual instance, every time it is called. That one
 * instance is reused within a scope is the business of the context, whose {@code get()} creates the instance once
 * and hands the same one out afterwards.
 */
class BeanCreateTest {

    @ApplicationScoped
    public static class ScopedItem {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        public int ping() {
            return 1;
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Singleton
    public static class SingleItem {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        public int ping() {
            return 1;
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class ScopedCallback {
        boolean requestActive;

        @PostConstruct
        void initialized() {
            requestActive = CDI.current().getBeanManager().getContext(RequestScoped.class).isActive();
        }
    }

    @Test
    void freshScopedCreationActivatesRequestContextDuringPostConstruct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<ScopedCallback> bean = beanOf(manager, ScopedCallback.class);
            CreationalContext<ScopedCallback> creation = manager.createCreationalContext(bean);
            ScopedCallback created = bean.create(creation);
            assertTrue(created.requestActive);
            bean.destroy(created, creation);
            assertThrows(jakarta.enterprise.context.ContextNotActiveException.class,
                () -> manager.getContext(RequestScoped.class));
        }
    }

    @Dependent
    public static class OwnedDependency {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Singleton
    public static class SingleOwner {
        @Inject OwnedDependency dependency;
    }

    @Test
    void freshSingletonOwnsItsDependentsAndLeavesTheScopedInstanceAlive() {
        OwnedDependency.DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            SingleOwner held = context.getBean(SingleOwner.class);
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<SingleOwner> bean = beanOf(manager, SingleOwner.class);
            CreationalContext<SingleOwner> firstContext = manager.createCreationalContext(bean);
            CreationalContext<SingleOwner> secondContext = manager.createCreationalContext(bean);
            SingleOwner first = bean.create(firstContext);
            SingleOwner second = bean.create(secondContext);

            assertNotSame(held, first);
            assertNotSame(first, second);
            assertNotSame(first.dependency, second.dependency);
            bean.destroy(first, firstContext);
            assertEquals(1, OwnedDependency.DESTROYED.get());
            firstContext.release();
            assertEquals(1, OwnedDependency.DESTROYED.get());
            assertSame(held, context.getBean(SingleOwner.class));
            bean.destroy(second, secondContext);
            assertEquals(2, OwnedDependency.DESTROYED.get());
        }
        assertEquals(3, OwnedDependency.DESTROYED.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanManager manager, Class<T> type) {
        return (Bean<T>) manager.resolve(manager.getBeans(type));
    }

    @Test
    void creatingABeanOfANormalScopeCreatesANewInstanceEachTime() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<ScopedItem> bean = beanOf(manager, ScopedItem.class);
            CreationalContext<ScopedItem> firstContext = manager.createCreationalContext(bean);
            CreationalContext<ScopedItem> secondContext = manager.createCreationalContext(bean);
            ScopedItem.DESTROYED.set(0);

            ScopedItem first = bean.create(firstContext);
            ScopedItem second = bean.create(secondContext);

            assertNotSame(first, second);
            assertEquals(1, first.ping());

            bean.destroy(first, firstContext);
            assertEquals(1, ScopedItem.DESTROYED.get());
            bean.destroy(second, secondContext);
            assertEquals(2, ScopedItem.DESTROYED.get());
        }
    }

    @Test
    void theContextCreatesTheInstanceOfItsScopeOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<ScopedItem> bean = beanOf(manager, ScopedItem.class);
            Context applicationContext = manager.getContext(ApplicationScoped.class);

            ScopedItem held = applicationContext.get(bean, manager.createCreationalContext(bean));
            ScopedItem again = applicationContext.get(bean, manager.createCreationalContext(bean));
            ScopedItem created = bean.create(manager.createCreationalContext(bean));

            assertSame(held, again);
            assertSame(held, applicationContext.get(bean));
            assertNotSame(held, created);
        }
    }

    @Test
    void creatingASingletonCreatesANewInstanceEachTime() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<SingleItem> bean = beanOf(manager, SingleItem.class);
            CreationalContext<SingleItem> firstContext = manager.createCreationalContext(bean);
            SingleItem.DESTROYED.set(0);

            SingleItem first = bean.create(firstContext);
            SingleItem second = bean.create(manager.createCreationalContext(bean));
            SingleItem reference = (SingleItem) manager.getReference(bean, SingleItem.class,
                manager.createCreationalContext(bean));

            assertNotSame(first, second);
            assertNotSame(first, reference);
            assertSame(reference, context.getBean(SingleItem.class));

            bean.destroy(first, firstContext);
            assertEquals(1, SingleItem.DESTROYED.get());
        }
    }
}
