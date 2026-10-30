package org.example.cdi.extension;

import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A custom normal scope whose context a portable extension adds with {@code AfterBeanDiscovery.addContext} is served
 * by that context in an SE container: a bean of the scope is created into it and found in it again.
 */
class PortableCustomNormalScopeTest {

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
     * A bean of the scope.
     */
    @Cloudy
    public static class Mist {
        public String name() {
            return "mist";
        }
    }

    /** The context of the scope, which holds what it creates. */
    static class CloudyContext implements AlterableContext {
        final Map<Contextual<?>, Object[]> held = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return Cloudy.class;
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

    static class AddingCloudy implements Extension {
        final CloudyContext context = new CloudyContext();

        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(context);
        }
    }

    @Test
    @Disabled("A custom normal scope is compiled into the application scope, and a context added at runtime "
        + "registers a Micronaut scope for the scope's own annotation, which no bean names. CONFORMANCE, "
        + "'Known limitations a review has named'.")
    void aBeanOfTheScopeIsHeldByTheContextTheExtensionAdded() {
        AddingCloudy adding = new AddingCloudy();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Mist.class).addExtensions(adding).initialize()) {
            assertEquals("mist", container.select(Mist.class).get().name());
            assertEquals(1, adding.context.held.size(), "the instance is held by the extension's context");
            Object held = adding.context.held.values().iterator().next()[0];
            assertSame(held, container.getBeanManager().getContext(Cloudy.class)
                .get(container.getBeanManager().getBeans(Mist.class).iterator().next()));
        }
    }
}
