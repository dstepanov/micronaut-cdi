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

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A parameter of an observer method is an injection point like any other (section 10.4.2): a {@code List<Crate>}
 * is the bean of that type, resolved by its full type and its qualifiers - the produced list - and not the beans
 * of the element type put together, as a disposer parameter and a field are resolved.
 */
class ObserverContainerInjectionTest {

    static final AtomicReference<List<Crate>> QUALIFIED = new AtomicReference<>();
    static final AtomicReference<List<Crate>> UNQUALIFIED = new AtomicReference<>();
    static final AtomicReference<Optional<Crate>> SPARE = new AtomicReference<>();
    static final AtomicReference<Crate[]> PALLET = new AtomicReference<>();
    static final AtomicReference<Label> LOOKED_UP = new AtomicReference<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Stacked {
    }

    @Dependent
    public static class Crate {
    }

    @Dependent
    public static class Label {
    }

    public record Delivery() {
    }

    @Dependent
    public static class Stacks {

        @Produces
        @Stacked
        List<Crate> stacked() {
            return List.of(new Crate(), new Crate(), new Crate());
        }

        @Produces
        List<Crate> loose() {
            return List.of(new Crate(), new Crate());
        }

        @Produces
        List<String> labels() {
            return List.of("a");
        }

        @Produces
        Optional<Crate> spare() {
            return Optional.of(new Crate());
        }

        @Produces
        Crate[] pallet() {
            return new Crate[4];
        }
    }

    @Dependent
    public static class Receiver {

        void qualified(@Observes Delivery delivery, @Stacked List<Crate> crates) {
            QUALIFIED.set(crates);
        }

        void unqualified(@Observes Delivery delivery, List<Crate> crates) {
            UNQUALIFIED.set(crates);
        }

        void optional(@Observes Delivery delivery, Optional<Crate> spare) {
            SPARE.set(spare);
        }

        void array(@Observes Delivery delivery, Crate[] pallet) {
            PALLET.set(pallet);
        }

        void lookup(@Observes Delivery delivery, Instance<Label> labels) {
            LOOKED_UP.set(labels.get());
        }
    }

    @Test
    void aContainerParameterOfAnObserverIsTheBeanOfItsFullType() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(BeanManager.class).getEvent().select(Delivery.class).fire(new Delivery());

            assertNotNull(QUALIFIED.get());
            assertEquals(3, QUALIFIED.get().size(), "the qualified produced list");
            assertNotNull(UNQUALIFIED.get());
            assertEquals(2, UNQUALIFIED.get().size(), "the produced list of the default qualifier");
            assertNotNull(SPARE.get());
            assertEquals(true, SPARE.get().isPresent(), "the produced optional");
            assertNotNull(PALLET.get());
            assertEquals(4, PALLET.get().length, "the produced array");
            assertNotNull(LOOKED_UP.get(), "the lookup of the type it was injected as");
        } finally {
            QUALIFIED.set(null);
            UNQUALIFIED.set(null);
            SPARE.set(null);
            PALLET.set(null);
            LOOKED_UP.set(null);
        }
    }
}
