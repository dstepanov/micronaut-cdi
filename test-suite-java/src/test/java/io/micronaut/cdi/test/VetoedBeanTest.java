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
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class annotated {@code @Vetoed} is not discovered as a bean.
 *
 * <p>{@code jakarta.enterprise.inject.Vetoed} says of a class that it is not to be a bean however it is otherwise
 * annotated. It is a way of writing a class that carries a bean defining annotation - because something else reads
 * it, or because a base class in the same source file needs one - and keeping it out of the container. On a
 * package it keeps every class of that package out the same way.</p>
 */
class VetoedBeanTest {

    @Test
    void aVetoedClassIsNotABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(0, container.getBeans(Refused.class).size());
            assertTrue(container.createInstance().select(Refused.class).isUnsatisfied());
        }
    }

    @Test
    void nothingOfAVetoedPackageIsABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(0, container.getBeans(io.micronaut.cdi.test.vetoed.Unwanted.class).size());
            assertTrue(container.createInstance()
                .select(io.micronaut.cdi.test.vetoed.Unwanted.class).isUnsatisfied());
        }
    }

    /**
     * Carries a scope and is vetoed all the same.
     */
    @Vetoed
    @Dependent
    public static class Refused {
    }
}
