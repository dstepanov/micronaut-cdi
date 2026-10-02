package org.example.cdi.extension;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A context a portable extension adds for a scope holds the instances of that scope, and the container goes through
 * it: a reference resolves to the instance it holds, {@code Bean.create()} still creates a new instance it does not
 * hold, and {@code AlterableContext.destroy} destroys the one it holds. Section 2.5.2 makes a context responsible
 * for destroying the instances it creates, so what such a context still holds as the container closes is its own
 * to destroy, and the container leaves it alone.
 */
class PortableContextCreationTest {

    static final AtomicInteger DESTROYED = new AtomicInteger();

    @RequestScoped
    public static class Stay {
        public String name() {
            return "stay";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    /** A context that is always active, and destroys what it holds the way the specification has a context do. */
    static class HoldingRequestContext implements AlterableContext {
        final Map<Contextual<?>, Object[]> held = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) held.computeIfAbsent(contextual,
                key -> new Object[] {contextual.create(creationalContext), creationalContext})[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            Object[] entry = held.get(contextual);
            return entry == null ? null : (T) entry[0];
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void destroy(Contextual<?> contextual) {
            Object[] entry = held.remove(contextual);
            if (entry != null) {
                ((Contextual) contextual).destroy(entry[0], (CreationalContext) entry[1]);
            }
        }
    }

    static class Adding implements Extension {
        final HoldingRequestContext context = new HoldingRequestContext();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(context);
        }
    }

    static class SwitchableContext extends HoldingRequestContext {
        boolean active;

        @Override
        public boolean isActive() {
            return active;
        }
    }

    static class AddingTwo implements Extension {
        final SwitchableContext first = new SwitchableContext();
        final SwitchableContext second = new SwitchableContext();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(first);
            event.addContext(second);
        }
    }

    @Test
    void contextLookupRequiresExactlyOneActiveContext() {
        AddingTwo adding = new AddingTwo();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Stay.class).addExtensions(adding).initialize()) {
            BeanManager manager = container.getBeanManager();
            assertEquals(2, manager.getContexts(RequestScoped.class).size());
            assertThrows(ContextNotActiveException.class, () -> manager.getContext(RequestScoped.class));
            assertThrows(ContextNotActiveException.class, () -> container.select(Stay.class).get().name());

            // An inactive first context must not hide the active second one.
            adding.second.active = true;
            assertSame(adding.second, manager.getContext(RequestScoped.class));
            assertEquals("stay", container.select(Stay.class).get().name());
            assertEquals(0, adding.first.held.size());
            assertEquals(1, adding.second.held.size());

            adding.first.active = true;
            assertThrows(IllegalStateException.class, () -> manager.getContext(RequestScoped.class));
            assertThrows(IllegalStateException.class, () -> container.select(Stay.class).get().name());
            assertEquals(0, adding.first.held.size(), "Ambiguity must be detected before creating an instance");

            adding.second.active = false;
            assertSame(adding.first, manager.getContext(RequestScoped.class));
            assertEquals("stay", container.select(Stay.class).get().name());
            assertEquals(1, adding.first.held.size());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanManager manager, Class<T> type) {
        return (Bean<T>) manager.resolve(manager.getBeans(type));
    }

    @Test
    void theContainerCreatesResolvesAndDestroysThroughAContextAnExtensionAdded() {
        DESTROYED.set(0);
        Adding adding = new Adding();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Stay.class).addExtensions(adding).initialize()) {
            BeanManager manager = container.getBeanManager();
            Bean<Stay> bean = beanOf(manager, Stay.class);

            // a reference resolves to the instance the extension's context holds
            assertEquals("stay", container.select(Stay.class).get().name());
            assertEquals(1, adding.context.held.size());
            Context context = manager.getContext(RequestScoped.class);
            Stay held = context.get(bean);
            assertSame(held, context.get(bean, manager.createCreationalContext(bean)));

            // creating the bean creates a new instance, which the context does not hold
            CreationalContext<Stay> creationalContext = manager.createCreationalContext(bean);
            Stay created = bean.create(creationalContext);
            assertNotSame(held, created);
            assertEquals(1, adding.context.held.size());
            bean.destroy(created, creationalContext);
            assertEquals(1, DESTROYED.get());

            // destroying the bean through the context destroys the instance it holds, and the next is a new one
            ((AlterableContext) context).destroy(bean);
            assertEquals(2, DESTROYED.get());
            assertEquals(0, adding.context.held.size());
            assertEquals("stay", container.select(Stay.class).get().name());
            assertNotSame(held, context.get(bean));
        }
        // the instance the context still held as the container closed is the context's to destroy
        assertEquals(2, DESTROYED.get());
        assertEquals(1, adding.context.held.size());
    }
}
