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
import jakarta.enterprise.inject.spi.BeanContainer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A primitive and the class that boxes it are one type for observer resolution (CDI 5.0, section 9.3.2), on either
 * side, while an array of a primitive stays a type of its own.
 */
class PrimitiveEventMatchingTest {

    @Test
    void primitivesAndTheirWrappersMatchEitherWay() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            assertTrue(container.isMatchingEvent(int.class, Set.of(), int.class, Set.of()));
            assertTrue(container.isMatchingEvent(Integer.class, Set.of(), int.class, Set.of()));
            assertTrue(container.isMatchingEvent(int.class, Set.of(), Integer.class, Set.of()));
            assertFalse(container.isMatchingEvent(int[].class, Set.of(), Integer[].class, Set.of()));
        }
    }
}
