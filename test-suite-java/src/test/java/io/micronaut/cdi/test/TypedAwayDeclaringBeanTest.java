/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean whose {@code Typed} leaves its own class out of its bean types, or names no type at all, is still a bean: it
 * declares its observer methods, inherited ones included, and its producers (CDI 4.1 sections 2.2.2, 3.2 and 9.4),
 * though a lookup of its class does not find it.
 */
class TypedAwayDeclaringBeanTest {

    static final List<String> HEARD = new CopyOnWriteArrayList<>();

    @Test
    void theObserversOfABeanTypedAwayFromItsClassAreNotified() {
        HEARD.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Knock.class).fire(new Knock());
        }
        assertEquals(List.of("Listener", "Scoped", "Sublistener"), HEARD.stream().sorted().toList());
    }

    @Test
    void theProducersOfABeanTypedAwayFromItsClassProduce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            List<String> produced = container.createInstance().select(Produced.class).stream()
                .map(Produced::by).sorted().toList();
            assertEquals(List.of("Factory", "ScopedFactory"), produced);
            // and the classes are still no bean types of the factories
            assertTrue(container.createInstance().select(Factory.class).isUnsatisfied());
            assertTrue(container.createInstance().select(ScopedFactory.class).isUnsatisfied());
            assertTrue(container.getBeans(Factory.class).isEmpty());
        }
    }

    @Test
    void theInstanceDisposerOfABeanTypedAwayFromItsClassDisposes() {
        DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            jakarta.enterprise.inject.Instance.Handle<Disposable> handle =
                container.createInstance().select(Disposable.class).getHandle();
            handle.get();
            handle.destroy();
        }
        assertEquals(List.of("disposed"), DISPOSED);
    }

    @Test
    void aBeanTypedAwayFromItsClassIsCreatedThroughItsBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            jakarta.enterprise.inject.spi.Bean<?> listener = container.getBeans(Object.class).stream()
                .filter(bean -> bean.getBeanClass() == Listener.class).findFirst().orElseThrow();
            Object created = create(listener, container);
            assertTrue(created instanceof Listener);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T create(jakarta.enterprise.inject.spi.Bean<T> bean, CdiBeanContainer container) {
        return bean.create(container.createCreationalContext(bean));
    }

    static final List<String> DISPOSED = new CopyOnWriteArrayList<>();

    /**
     * What the factory below disposes of.
     */
    public static final class Disposable {
    }

    /**
     * A bean of no type, producing and disposing through methods of its instance.
     */
    @Dependent
    @Typed
    public static class DisposingFactory {
        @Produces
        Disposable open() {
            return new Disposable();
        }

        void close(@jakarta.enterprise.inject.Disposes Disposable disposable) {
            DISPOSED.add("disposed");
        }
    }

    /**
     * The event observed.
     */
    public record Knock() {
    }

    /**
     * What the factories produce.
     *
     * @param by The factory that produced it
     */
    public record Produced(String by) {
    }

    /**
     * A bean of no type at all, observing.
     */
    @Dependent
    @Typed
    public static class Listener {
        void hear(@Observes Knock knock) {
            HEARD.add(name());
        }

        String name() {
            return "Listener";
        }
    }

    /**
     * A subclass of no type either, which inherits the observer method.
     */
    @Dependent
    @Typed
    public static class Sublistener extends Listener {
        @Override
        String name() {
            return "Sublistener";
        }
    }

    /**
     * A bean in a normal scope typed as {@code Object} alone, observing.
     */
    @ApplicationScoped
    @Typed(Object.class)
    public static class Scoped {
        void hear(@Observes Knock knock) {
            HEARD.add("Scoped");
        }
    }

    /**
     * A bean of no type, producing.
     */
    @Dependent
    @Typed
    public static class Factory {
        @Produces
        Produced produce() {
            return new Produced("Factory");
        }
    }

    /**
     * A bean in a normal scope typed as {@code Object} alone, producing.
     */
    @ApplicationScoped
    @Typed(Object.class)
    public static class ScopedFactory {
        @Produces
        @Dependent
        Produced produce() {
            return new Produced("ScopedFactory");
        }
    }
}
