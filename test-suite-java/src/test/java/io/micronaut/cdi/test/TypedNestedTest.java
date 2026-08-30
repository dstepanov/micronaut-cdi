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
import jakarta.enterprise.inject.Typed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean may narrow its types to a nested one, and a bean that narrows them to nothing keeps only
 * {@code Object} (section 2.2.4). Both are shapes the compile-time check sees in binary names, where a nested
 * type is written with a dollar.
 */
class TypedNestedTest {

    @Test
    void aBeanMayNarrowItsTypesToANestedInterface() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("nested", container.createInstance().select(Chimes.class).get().ring());
            // narrowed away, so it is not resolvable as itself
            assertTrue(container.createInstance().select(NestedChimes.class).isUnsatisfied());
        }
    }

    @Test
    void aBeanThatNarrowsItsTypesToNothingKeepsOnlyObject() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertTrue(container.createInstance().select(Silent.class).isUnsatisfied());
            assertTrue(container.createInstance().select(Silencer.class).isUnsatisfied());
        }
    }

    /**
     * The nested interface a bean narrows itself to.
     */
    public interface Chimes {

        /**
         * What it says.
         *
         * @return The sound
         */
        String ring();
    }

    /**
     * Narrowed to the nested interface, which the metadata names with a dollar.
     */
    @Dependent
    @Typed(Chimes.class)
    public static class NestedChimes implements Chimes {

        @Override
        public String ring() {
            return "nested";
        }
    }

    /**
     * What the silenced bean would otherwise be resolvable as.
     */
    public interface Silent {
    }

    /**
     * Narrowed to nothing: only Object is left.
     */
    @Dependent
    @Typed
    public static class Silencer implements Silent {
    }
}
