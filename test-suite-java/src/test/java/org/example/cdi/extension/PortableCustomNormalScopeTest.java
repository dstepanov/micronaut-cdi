package org.example.cdi.extension;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A custom normal scope whose context a portable extension adds with {@code AfterBeanDiscovery.addContext} is served
 * by that context in an SE container: a bean of the scope - a class declaring it, inheriting it or carrying it with
 * a stereotype, and a producer declaring it - is created into it and found in it again, an inactive context is not
 * stepped around, and what it holds is destroyed as it ends. Where no context of the scope is registered the bean is
 * held in the application scope.
 */
class PortableCustomNormalScopeTest {

    static final List<String> DESTROYED = Collections.synchronizedList(new ArrayList<>());

    /**
     * A normal scope of the application, which nothing but the extension's context serves.
     */
    @NormalScope
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Cloudy {
    }

    /**
     * A stereotype carrying the scope.
     */
    @Stereotype
    @Cloudy
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Overcast {
    }

    /**
     * A bean of the scope.
     */
    @Cloudy
    public static class Mist {
        public String name() {
            return "mist";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.add("mist");
        }
    }

    /**
     * A bean that inherits the scope from its superclass.
     */
    public static class Drizzle extends Mist {
        @Override
        public String name() {
            return "drizzle";
        }
    }

    /**
     * A bean whose stereotype carries the scope.
     */
    @Overcast
    public static class Haze {
        public String name() {
            return "haze";
        }
    }

    /**
     * What a producer of the scope produces.
     */
    public static class Vapor {
        private final String name;

        public Vapor() {
            this("");
        }

        Vapor(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /**
     * A bean that counts its instances, to tell one held instance from one per reference.
     */
    @Cloudy
    public static class Counted {
        static final AtomicInteger CREATED = new AtomicInteger();

        @jakarta.annotation.PostConstruct
        void created() {
            CREATED.incrementAndGet();
        }

        public int count() {
            return CREATED.get();
        }
    }

    @Dependent
    public static class VaporProducer {
        @Produces
        @Cloudy
        Vapor vapor() {
            return new Vapor("vapor");
        }
    }

    /** The context of the scope, which holds what it creates. */
    static class CloudyContext implements AlterableContext {
        final Map<Contextual<?>, Object[]> held = new ConcurrentHashMap<>();
        volatile boolean active = true;

        @Override
        public Class<? extends Annotation> getScope() {
            return Cloudy.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            if (!active) {
                throw new ContextNotActiveException("cloudy");
            }
            return (T) held.computeIfAbsent(contextual,
                key -> new Object[] {contextual.create(creationalContext), creationalContext})[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            if (!active) {
                throw new ContextNotActiveException("cloudy");
            }
            Object[] entry = held.get(contextual);
            return entry == null ? null : (T) entry[0];
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void destroy(Contextual<?> contextual) {
            Object[] entry = held.remove(contextual);
            if (entry != null) {
                ((Contextual) contextual).destroy(entry[0], (CreationalContext) entry[1]);
            }
        }

        /** Ends the context: everything it holds is destroyed. */
        void end() {
            for (Contextual<?> contextual : List.copyOf(held.keySet())) {
                destroy(contextual);
            }
        }
    }

    static class AddingCloudy implements Extension {
        final CloudyContext context = new CloudyContext();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(context);
        }
    }

    private static SeContainer start(AddingCloudy adding, Class<?>... beans) {
        SeContainerInitializer initializer = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(beans);
        if (adding != null) {
            initializer.addExtensions(adding);
        }
        return initializer.initialize();
    }

    private static Object heldFor(SeContainer container, Class<?> type) {
        BeanManager manager = container.getBeanManager();
        Bean<?> bean = manager.resolve(manager.getBeans(type));
        return manager.getContext(Cloudy.class).get(bean);
    }

    @Test
    void aBeanOfTheScopeIsHeldByTheContextTheExtensionAdded() {
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = start(adding, Mist.class)) {
            assertEquals("mist", container.select(Mist.class).get().name());
            assertEquals(1, adding.context.held.size(), "the instance is held by the extension's context");
            Object held = adding.context.held.values().iterator().next()[0];
            assertSame(held, heldFor(container, Mist.class));
            assertEquals(Cloudy.class, container.getBeanManager().resolve(
                container.getBeanManager().getBeans(Mist.class)).getScope(), "the scope the author wrote");
        }
    }

    @Test
    void aBeanThatInheritsTheScopeIsHeldByTheContext() {
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = start(adding, Drizzle.class)) {
            assertEquals("drizzle", container.select(Drizzle.class).get().name());
            assertEquals(1, adding.context.held.size());
            assertSame(adding.context.held.values().iterator().next()[0], heldFor(container, Drizzle.class));
        }
    }

    @Test
    void aBeanWhoseStereotypeCarriesTheScopeIsHeldByTheContext() {
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = start(adding, Haze.class)) {
            assertEquals("haze", container.select(Haze.class).get().name());
            assertEquals(1, adding.context.held.size());
            assertSame(adding.context.held.values().iterator().next()[0], heldFor(container, Haze.class));
            assertEquals(Cloudy.class, container.getBeanManager().resolve(
                container.getBeanManager().getBeans(Haze.class)).getScope());
        }
    }

    @Test
    void whatAProducerOfTheScopeProducesIsHeldByTheContext() {
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = start(adding, VaporProducer.class, Vapor.class)) {
            assertEquals("vapor", container.select(Vapor.class).get().name());
            assertEquals(1, adding.context.held.size());
            Object held = adding.context.held.values().iterator().next()[0];
            assertEquals("vapor", ((Vapor) held).name());
            assertSame(held, heldFor(container, Vapor.class));
        }
    }

    @Test
    void anInactiveContextIsNotSteppedAround() {
        AddingCloudy adding = new AddingCloudy();
        adding.context.active = false;
        try (SeContainer container = start(adding, Mist.class)) {
            Mist mist = container.select(Mist.class).get();
            assertThrows(ContextNotActiveException.class, mist::name);
            assertTrue(adding.context.held.isEmpty(), "nothing was created elsewhere in its place");
        }
    }

    @Test
    void whatTheContextHoldsIsDestroyedAsItEnds() {
        DESTROYED.clear();
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = start(adding, Mist.class)) {
            Mist mist = container.select(Mist.class).get();
            assertEquals("mist", mist.name());
            adding.context.end();
            assertEquals(List.of("mist"), DESTROYED);
            assertTrue(adding.context.held.isEmpty());
            // the reference outlives the instance: the next call creates one again in the context
            assertEquals("mist", mist.name());
            assertEquals(1, adding.context.held.size());
        }
    }

    @Test
    void withNoContextOfTheScopeTheBeanIsHeldInTheApplicationScope() {
        Counted.CREATED.set(0);
        try (SeContainer container = start(null, Counted.class)) {
            Counted first = container.select(Counted.class).get();
            assertEquals(1, first.count());
            assertEquals(1, first.count());
            assertEquals(1, container.select(Counted.class).get().count(), "one instance for every reference");
            assertEquals(Cloudy.class, container.getBeanManager().resolve(
                container.getBeanManager().getBeans(Counted.class)).getScope());
        }
    }
}
