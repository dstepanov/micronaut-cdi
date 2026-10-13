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
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default name of a bean is the unqualified name of its class with the first letter made lower case.
 *
 * <p>Section 2.6.3 says so, and the unqualified name of a nested class is its own name rather than its outer
 * class's: a class {@code Inner} nested in {@code Outer} is named {@code inner}. The name Micronaut reports for
 * such a class is the binary one, {@code Outer$Inner}, and the default name was being read straight off it.</p>
 */
class DefaultBeanNameTest {

    @Test
    void aNestedBeanClassIsNamedAfterItselfAndNotItsOuterClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans("lantern");
            assertEquals(1, beans.size());
            assertEquals(Lantern.class, beans.iterator().next().getBeanClass());
            assertEquals("lantern", container.getBeans(Lantern.class).iterator().next().getName());

            Set<Bean<?>> dependent = container.getBeans("candle");
            assertEquals(1, dependent.size());
            assertEquals(Candle.class, dependent.iterator().next().getBeanClass());
        }
    }

    @Test
    void aProducerKeepsTheNameOfItsMember() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(1, container.getBeans("wick").size());
        }
    }

    @Test
    void aProducerNamedAsAPropertyIsNamedAsJavaBeansNamesTheProperty() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            // the name of the property of a getter whose name goes on in two capitals keeps them
            assertEquals(1, container.getBeans("URL").size());
            assertEquals(0, container.getBeans("uRL").size());
            assertEquals(1, container.getBeans("flameColour").size());
            // a method that takes a parameter is no getter, and is named after itself
            assertEquals(1, container.getBeans("getWax").size());
        }
    }

    /**
     * A nested bean class that asks for the default name.
     */
    @Singleton
    @Named
    public static class Lantern {
    }

    /**
     * A nested bean class in a scope of the specification's own, asking for the same name.
     */
    @Dependent
    @Named
    public static class Candle {
    }

    /**
     * Declares a named producer, whose default name is the name of the member rather than of any class.
     */
    @Singleton
    public static class LanternWorks {

        /**
         * @return The wick
         */
        @Produces
        @Dependent
        @Named
        String wick() {
            return "wick";
        }

        /**
         * @return The address of the works
         */
        @Produces
        @Dependent
        @Named
        java.net.URI getURL() {
            return java.net.URI.create("https://lantern.example");
        }

        /**
         * @return The colour of the flame
         */
        @Produces
        @Dependent
        @Named
        StringBuilder getFlameColour() {
            return new StringBuilder("yellow");
        }

        /**
         * @param wick The wick, which makes this no getter
         * @return The wax
         */
        @Produces
        @Dependent
        @Named
        Integer getWax(@Named("wick") String wick) {
            return wick.length();
        }
    }
}
