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
import io.micronaut.cdi.internal.type.SpecificationTypes;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An array of a parameterized type is a bean type with the arguments of its element type (section 2.1.2.1): a
 * producer of {@code Crate<String>[]} is resolved by {@code Crate<String>[]}, and reports that type rather than
 * the raw {@code Crate[]}.
 */
class GenericArrayBeanTypeTest {

    @Test
    void aProducedArrayOfAParameterizedTypeKeepsItsArguments() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Type crates = new TypeLiteral<Crate<String>[]>() { }.getType();
            Set<Bean<?>> beans = container.getBeans(crates);
            assertEquals(1, beans.size(), "Crate<String>[] resolves the producer");
            assertTrue(beans.iterator().next().getTypes().contains(crates), "and the bean has the type itself");
            Crate<String>[] produced = context.getBean(Consumer.class).crates.get();
            assertEquals("produced", produced[0].value);
            assertEquals("produced", context.getBean(Consumer.class).direct[0].value,
                "direct field injection uses the same exact array bean");
            assertTrue(container.getBeans(new TypeLiteral<Crate<Long>[]>() { }.getType()).isEmpty(),
                "Crate<Long>[] does not");
            for (Type incompatible : new Type[]{Object[].class, Crate[].class,
                new TypeLiteral<Crate<?>[]>() { }.getType()}) {
                assertTrue(container.getBeans(incompatible).isEmpty(), "no CDI bean for " + incompatible);
                assertTrue(context.findBean(SpecificationTypes.argumentOf(incompatible)).isEmpty(),
                    "Micronaut candidate resolution also rejects " + incompatible);
            }
            assertTrue(context.getBean(Consumer.class).wildcards.isUnsatisfied());
            assertTrue(context.getBean(Consumer.class).objects.isUnsatisfied());
            Crate<?>[] throughObject = (Crate<?>[]) context.getBean(Consumer.class).integers.get();
            assertEquals(123, throughObject[0].value, "Object remains a bean type of a generic array producer");
        }
    }

    static class Crate<T> {
        final T value;

        Crate(T value) {
            this.value = value;
        }
    }

    @Dependent
    static class CrateProducer {
        @SuppressWarnings("unchecked")
        @Produces
        Crate<String>[] crates() {
            return new Crate[]{new Crate<>("produced")};
        }

        @SuppressWarnings("unchecked")
        @Produces
        @jakarta.inject.Named("array-through-object")
        Crate<Integer>[] integers() {
            return new Crate[]{new Crate<>(123)};
        }
    }

    @Dependent
    static class Consumer {
        @jakarta.inject.Inject
        Instance<Crate<String>[]> crates;

        @jakarta.inject.Inject
        Crate<String>[] direct;

        @jakarta.inject.Inject
        Instance<Crate<?>[]> wildcards;

        @jakarta.inject.Inject
        Instance<Object[]> objects;

        @jakarta.inject.Inject
        @jakarta.inject.Named("array-through-object")
        Instance<Object> integers;
    }
}
