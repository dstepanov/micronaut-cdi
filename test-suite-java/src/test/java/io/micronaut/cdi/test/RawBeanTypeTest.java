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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A raw bean type and the types above it: the supertypes of a raw type are erased, as the language has them, so
 * a producer of the raw {@code Bin} has the bean types {@code Bin}, {@code Tray} and {@code Object}, and is no
 * bean of {@code Tray<String>} (sections 3.3.1 and 2.4.2.4). A raw bean type matches a parameterized required
 * type whose arguments are {@code Object} or unbounded variables, and a wildcard is neither.
 */
class RawBeanTypeTest {

    @Test
    @SuppressWarnings("rawtypes")
    void theSupertypesOfARawBeanTypeAreErased() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertTrue(container.getBeans(new TypeLiteral<Tray<String>>() { }.getType()).isEmpty(),
                "a raw Bin is no Tray<String>");
            Set<Bean<?>> beans = container.getBeans(Tray.class);
            assertEquals(1, beans.size(), "but it is a raw Tray");
            assertEquals(Set.<Type>of(Bin.class, Tray.class, Object.class), beans.iterator().next().getTypes());
        }
    }

    @Test
    void aRawBeanTypeDoesNotMatchAWildcard() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertTrue(container.getBeans(new TypeLiteral<Bin<?>>() { }.getType()).isEmpty(),
                "a raw Bin is no Bin<?>: only Object and an unbounded variable are matched by a raw type");
            assertEquals(1, container.getBeans(new TypeLiteral<Bin<Object>>() { }.getType()).size(),
                "but it is a Bin<Object>");
        }
    }

    interface Tray<T> {
    }

    static class Bin<T> implements Tray<T> {
    }

    @Dependent
    static class BinProducer {
        @SuppressWarnings("rawtypes")
        @Produces
        Bin bin() {
            return new Bin();
        }
    }
}
