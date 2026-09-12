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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An abstract class is not a managed bean, however it is annotated.
 *
 * <p>Section 3.1.1 requires the class of a managed bean to be concrete, so an abstract class that carries a scope
 * is not a bean and is not a candidate for anything. Its concrete subclass is, and an injection point of the
 * abstract type resolves to that subclass alone.</p>
 */
class AbstractBeanTest {

    @Test
    void anAbstractClassIsNotAmongTheBeansOfItsType() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(1, container.getBeans(Mooring.class).size());
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("The definition compiled for an abstract class still takes part in "
        + "resolution, so a lookup of the abstract type is ambiguous; see CONFORMANCE.md")
    void aLookupOfTheAbstractTypeResolvesToItsConcreteSubclass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("tied", container.createInstance().select(Mooring.class).get().hold());
        }
    }

    /**
     * Abstract, and annotated with a scope all the same.
     */
    @Dependent
    public abstract static class Mooring {

        public abstract String hold();
    }

    /**
     * The one bean of the type.
     */
    @Dependent
    public static class Bollard extends Mooring {

        @Override
        public String hold() {
            return "tied";
        }
    }
}
