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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contextual instance of a bean in a normal scope that declares a producer of a dependent bean is the one each
 * product is produced on, and lives as long as its context: producing, iterating and destroying a product leaves it
 * be, and it is destroyed as the context ends (CDI 4.1 sections 3.2 and 6.4.2). Only a dependent declaring instance
 * obtained for one call of a producer, disposer or observer method is destroyed once the call completes.
 */
class NormalScopedFactoryLifecycleTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Test
    @Disabled("Micronaut Core destroys the factory it recorded for a product once the product is created, as a bean "
        + "of its own rather than as a dependent: for a bean in a normal scope that is its client proxy, whose "
        + "destruction removes the contextual instance from its scope (see CONFORMANCE.md, Open points in Micronaut "
        + "Core)")
    void theDeclaringInstanceOutlivesItsProductsAndIsDestroyedWithItsContext() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            for (Widget widget : lookup.select(Widget.class)) {
                EVENTS.add("each " + widget.name());
            }
            Instance.Handle<Widget> handle = lookup.select(Widget.class).getHandle();
            handle.get();
            handle.destroy();
            for (Gadget gadget : lookup.select(Gadget.class)) {
                EVENTS.add("each " + gadget.name());
            }
            EVENTS.add("stopping");
        }
        // nothing destroyed while the products were made, iterated and destroyed; both declaring instances with the
        // application context, in an order of its own
        assertEquals(List.of("each method", "each field", "stopping"), EVENTS.subList(0, Math.min(3, EVENTS.size())),
            EVENTS.toString());
        assertEquals(List.of("Storeroom destroyed", "Workshop destroyed"),
            EVENTS.subList(Math.min(3, EVENTS.size()), EVENTS.size()).stream().sorted().toList(), EVENTS.toString());
    }

    /**
     * Produced by a method.
     *
     * @param name What produced it
     */
    public record Widget(String name) {
    }

    /**
     * Produced by a field.
     *
     * @param name What produced it
     */
    public record Gadget(String name) {
    }

    /**
     * An application scoped bean producing a dependent bean by a method.
     */
    @ApplicationScoped
    public static class Workshop {

        @Produces
        @Dependent
        Widget widget() {
            return new Widget("method");
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("Workshop destroyed");
        }
    }

    /**
     * An application scoped bean producing a dependent bean by a field.
     */
    @ApplicationScoped
    public static class Storeroom {

        @Produces
        @Dependent
        Gadget gadget = new Gadget("field");

        @PreDestroy
        void destroyed() {
            EVENTS.add("Storeroom destroyed");
        }
    }
}
