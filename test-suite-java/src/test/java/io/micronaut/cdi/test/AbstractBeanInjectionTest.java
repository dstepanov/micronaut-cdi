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
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An injection point of an abstract type resolves to its concrete subclass, the definition compiled for the
 * abstract class taking no part in it: section 3.1.1 makes only the subclass a bean.
 */
class AbstractBeanInjectionTest {

    @Test
    void anInjectionPointOfTheAbstractTypeResolvesToItsConcreteSubclass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("tied", context.getBean(Dock.class).mooring.hold());
        }
    }

    /**
     * Asks for the abstract type.
     */
    @Dependent
    public static class Dock {

        @Inject
        AbstractBeanTest.Mooring mooring;
    }
}
