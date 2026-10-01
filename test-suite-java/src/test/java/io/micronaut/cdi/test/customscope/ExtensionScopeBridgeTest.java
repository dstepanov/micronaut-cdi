package io.micronaut.cdi.test.customscope;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.mapscope.MapContext;
import io.micronaut.cdi.test.extension.mapscope.MapScoped;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context of a scope an extension provides is keyed by the contextual it is handed, the way any
 * {@code Map<Contextual<?>, ...>} context is. Creation hands it a wrapper; lookup and destruction hand it the bean.
 * The two have to be one key. And destroying what the context holds destroys what was created with it.
 */
class ExtensionScopeBridgeTest {

    static final List<String> DESTROYED = Collections.synchronizedList(new ArrayList<>());

    @MapScoped
    public static class Scoped {

        public String id() {
            return "scoped";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.add("scoped");
        }
    }

    @Dependent
    public static class Resource {

        @PreDestroy
        void destroyed() {
            DESTROYED.add("resource");
        }
    }

    @MapScoped
    public static class ScopedWithResource {

        @Inject
        Resource resource;

        public String id() {
            return "with resource";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.add("scoped with resource");
        }
    }

    @Dependent
    public static class Holder {

        @Inject
        Instance<Scoped> scoped;
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theContextFindsTheInstanceByTheBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MapContext.STORE.clear();
            assertEquals("scoped", context.getBean(Scoped.class).id());
            assertEquals(1, MapContext.STORE.size(), "created in the extension's context");

            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean bean = container.resolve((Set) container.getBeans(Scoped.class));
            assertNotNull(container.getContext(MapScoped.class).get(bean), "the context holds it under the bean");
        }
    }

    @Test
    void instanceDestroyDestroysTheInstanceTheContextHolds() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MapContext.STORE.clear();
            DESTROYED.clear();
            Holder holder = context.getBean(Holder.class);
            Scoped scoped = holder.scoped.get();
            assertEquals("scoped", scoped.id());
            assertEquals(1, MapContext.STORE.size(), "created in the extension's context");

            holder.scoped.destroy(scoped);

            assertEquals(0, MapContext.STORE.size(), "the context let the instance go: " + MapContext.STORE);
            assertTrue(DESTROYED.contains("scoped"), "the instance was destroyed: " + DESTROYED);
        }
    }

    @Test
    void endingTheContextDestroysTheDependentsOfWhatItHeld() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MapContext.STORE.clear();
            DESTROYED.clear();
            assertEquals("with resource", context.getBean(ScopedWithResource.class).id());

            MapContext.endAll();

            assertTrue(DESTROYED.contains("scoped with resource"), "the instance was destroyed: " + DESTROYED);
            assertTrue(DESTROYED.contains("resource"), "its dependent was destroyed with it: " + DESTROYED);
        }
    }
}
