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
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A producer of an array may narrow its bean types with {@code Typed} to its array type (CDI 4.1 sections 2.2.2 and
 * 3.2.1): an array type is a legal bean type, and the product is injected and looked up by it.
 */
class TypedArrayProducerTest {

    @Test
    void anArrayProducerTypedToItsArrayTypeIsResolvedByIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.resolve(container.getBeans(Shelf.Volume[].class));
            assertEquals(Set.<Type>of(Shelf.Volume[].class, Object.class), bean.getTypes());
            Shelf.Volume[] looked = container.createInstance().select(Shelf.Volume[].class).get();
            assertEquals(2, looked.length);
            assertArrayEquals(looked, container.createInstance().select(Reader.class).get().volumes);
        }
    }

    /**
     * Produces an array, typed to the array type.
     */
    @Dependent
    public static class Shelf {

        /**
         * What the shelf holds.
         *
         * @param title The title
         */
        public record Volume(String title) {
        }

        static final Volume[] VOLUMES = {new Volume("one"), new Volume("two")};

        @Produces
        @Typed(Volume[].class)
        Volume[] volumes() {
            return VOLUMES;
        }
    }

    /**
     * Injected with the array.
     */
    @Dependent
    public static class Reader {
        @Inject
        Shelf.Volume[] volumes;
    }
}
