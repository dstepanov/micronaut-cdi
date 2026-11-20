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
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A subclass inherits no producer method of its superclass (CDI 4.1 section 4.2), a parameterized one of a normal
 * scope included: the products are the superclass's alone, to a lookup, to the deployment and to Micronaut.
 */
class InheritedProducerMethodsTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * Parameterized, produced in the application scope.
     *
     * @param <T> The type
     */
    public interface Gen<T> {
        T value();
    }

    /**
     * Produced as a dependent bean from a gen.
     */
    public record Sheet(String from) {
    }

    /**
     * Declares both producers.
     */
    @Dependent
    public static class Maker {
        @Produces
        Sheet sheet(Gen<String> gen) {
            return new Sheet(gen.value());
        }

        @Produces
        @ApplicationScoped
        Gen<String> gen() {
            return () -> "gen";
        }
    }

    /**
     * Inherits the producers, which are no producers of its own.
     */
    @Dependent
    public static class SubMaker extends Maker {
        @PreDestroy
        void destroyed() {
            EVENTS.add("submaker destroyed");
        }
    }

    /**
     * What the press below makes.
     */
    public record Leaflet() {
    }

    /**
     * Handed a sub maker for its product.
     */
    @Dependent
    public static class Press {
        @Produces
        Leaflet print(SubMaker maker) {
            return new Leaflet();
        }
    }

    @Test
    void theInheritedProducersProduceNothing() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Object> lookup = container.createInstance();
            Instance<Gen<String>> gens = lookup.select(new TypeLiteral<Gen<String>>() { });
            assertFalse(gens.isAmbiguous());
            assertEquals(1, gens.stream().count());
            assertEquals("gen", gens.get().value());
            assertEquals(1, container.getBeans(new TypeLiteral<Gen<String>>() { }.getType()).size());
            Instance<Sheet> sheets = lookup.select(Sheet.class);
            assertFalse(sheets.isAmbiguous());
            assertEquals(1, sheets.stream().count());
            assertEquals("gen", sheets.get().from());
            assertEquals(1, container.getBeans(Sheet.class).size());
            assertEquals(1, context.getBeansOfType(Sheet.class).size());
            assertEquals(1, lookup.select(SubMaker.class).stream().count());
        }
    }

    @Test
    void theSubclassHandedToAProducerIsDestroyedWithTheProduct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Leaflet> leaflets = context.getBean(CdiBeanContainer.class).createInstance().select(Leaflet.class);
            EVENTS.clear();
            leaflets.destroy(leaflets.get());
            assertEquals(List.of("submaker destroyed"), EVENTS);
        }
    }
}
