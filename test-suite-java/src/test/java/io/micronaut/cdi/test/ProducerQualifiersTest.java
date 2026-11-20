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
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A producer has the qualifiers it declares itself, and {@code Default} where it declares none: none of the
 * qualifiers of the class declaring it (CDI 4.1 sections 2.3.1 and 3.2).
 */
class ProducerQualifiersTest {

    /**
     * The qualifier, with a member that binds.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Shelf {
        String value();

        /**
         * The literal.
         */
        final class Literal extends AnnotationLiteral<Shelf> implements Shelf {
            private final String value;

            Literal(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return value;
            }
        }
    }

    /**
     * What is looked up.
     */
    public interface Item {
        String name();
    }

    /**
     * Produced by a field qualified otherwise than its class.
     *
     * @param name The name
     */
    public record Tool(String name) {
    }

    /**
     * Produced by a method that declares no qualifier.
     *
     * @param name The name
     */
    public record Part(String name) {
    }

    /**
     * Produced by a method of a class that is an item itself.
     *
     * @param name The name
     */
    public record Gadget(String name) implements Item {
    }

    /**
     * A bean qualified "upper", declaring a producer field qualified "lower" and a producer method declaring none.
     */
    @Dependent
    @Shelf("upper")
    public static class Cupboard {
        @Produces
        @Shelf("lower")
        Tool tool = new Tool("tool");

        @Produces
        Part part() {
            return new Part("part");
        }
    }

    /**
     * An item qualified "top", whose producer method of another item is qualified "bottom".
     */
    @Dependent
    @Shelf("top")
    public static class Drawer implements Item {
        @Override
        public String name() {
            return "drawer";
        }

        @Produces
        @Shelf("bottom")
        Gadget gadget() {
            return new Gadget("gadget");
        }
    }

    @Test
    void aProducerFieldHasItsOwnQualifiersAndNotItsClasss() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            assertTrue(lookup.select(Tool.class, new Shelf.Literal("upper")).isUnsatisfied());
            assertEquals("tool", lookup.select(Tool.class, new Shelf.Literal("lower")).get().name());
        }
    }

    @Test
    void aProducerMethodThatDeclaresNoQualifierHasDefaultAndNotItsClasss() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            assertTrue(lookup.select(Part.class, new Shelf.Literal("upper")).isUnsatisfied());
            assertEquals("part", lookup.select(Part.class, Default.Literal.INSTANCE).get().name());
        }
    }

    @Test
    void aProducerDoesNotMakeALookupOfItsClasssQualifierAmbiguous() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance<Item> top = lookup.select(Item.class, new Shelf.Literal("top"));
            assertTrue(top.isResolvable(), "only the drawer is an item qualified top");
            assertEquals("drawer", top.get().name());
            assertEquals("gadget", lookup.select(Item.class, new Shelf.Literal("bottom")).get().name());
        }
    }
}
