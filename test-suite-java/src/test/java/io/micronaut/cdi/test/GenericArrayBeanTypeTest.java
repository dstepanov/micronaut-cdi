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
            assertTrue(container.getBeans(new TypeLiteral<Crate<Integer>[]>() { }.getType()).isEmpty(),
                "Crate<Integer>[] does not");
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
    }

    @Dependent
    static class Consumer {
        @jakarta.inject.Inject
        Instance<Crate<String>[]> crates;
    }
}
