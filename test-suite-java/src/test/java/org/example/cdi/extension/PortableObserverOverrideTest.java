package org.example.cdi.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An observer method of a portable extension that a subclass overrides is the subclass's method alone (section 10.4,
 * an observer method is inherited only where it is not overridden): the overridden declaration is not an observer
 * of its own, and an override that does not observe is no observer at all.
 */
class PortableObserverOverrideTest {

    /**
     * A bean, so that the archive is not empty.
     */
    @ApplicationScoped
    public static class Lamp {
    }

    public static class BaseExtension implements Extension {
        final List<String> heard = new ArrayList<>();

        void before(@Observes BeforeBeanDiscovery event) {
            heard.add("base");
        }
    }

    public static class OverridingObserver extends BaseExtension {
        @Override
        void before(@Observes BeforeBeanDiscovery event) {
            heard.add("override");
        }
    }

    public static class OverridingWithoutObserving extends BaseExtension {
        @Override
        void before(BeforeBeanDiscovery event) {
            heard.add("not an observer");
        }
    }

    @Test
    void anOverriddenObserverIsNotifiedOnceAsTheOverride() {
        OverridingObserver extension = new OverridingObserver();
        try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Lamp.class).addExtensions(extension).initialize()) {
            assertEquals(List.of("override"), extension.heard);
        }
    }

    @Test
    void anOverrideThatDoesNotObserveIsNoObserver() {
        OverridingWithoutObserving extension = new OverridingWithoutObserving();
        try (SeContainer ignored = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Lamp.class).addExtensions(extension).initialize()) {
            assertEquals(List.of(), extension.heard);
        }
    }
}
