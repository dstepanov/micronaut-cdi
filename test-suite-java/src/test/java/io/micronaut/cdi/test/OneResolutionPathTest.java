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
import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection point, an {@code Instance} and the bean manager select the beans of a type through one path of the
 * container, so the three agree on what a type and its qualifiers resolve to.
 */
class OneResolutionPathTest {

    // -- a raw lookup of a parameterized bean

    @Dependent
    public static class Holder<T> {
    }

    public interface Box<T> {
    }

    @Dependent
    public static class StringBox implements Box<String> {
    }

    // -- a bean Micronaut's Replaces replaced

    public interface Service {
    }

    @Dependent
    public static class OriginalService implements Service {
    }

    @Dependent
    @Replaces(OriginalService.class)
    public static class ReplacingService implements Service {
    }

    // -- a definition Micronaut only indexes under a type it is not of

    public interface IndexMarker {
    }

    @Dependent
    @Indexed(IndexMarker.class)
    public static class IndexedOnly {
    }

    // -- an alternative no priority selected

    public interface Pricing {
    }

    @Dependent
    public static class StandardPricing implements Pricing {
    }

    @Dependent
    @Alternative
    public static class DiscountPricing implements Pricing {
    }

    // -- beans of Micronaut's own, injected into a bean of the specification

    public static class Clock {
    }

    @Bean
    public static class PlainClock extends Clock {
    }

    public interface Engine {
    }

    @Bean
    @Primary
    public static class MainEngine implements Engine {
    }

    @Bean
    public static class SpareEngine implements Engine {
    }

    // -- a bean of Micronaut's own injected with a type two beans of the specification have

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Fancy {
    }

    public interface Shape {
    }

    @Dependent
    public static class Circle implements Shape {
    }

    @Dependent
    @Fancy
    public static class Square implements Shape {
    }

    // -- a bean of the specification no qualifier admits beside a bean of Micronaut's own

    public interface Figure {
    }

    @Dependent
    @Fancy
    public static class FancyFigure implements Figure {
    }

    @Bean
    public static class PlainFigure implements Figure {
    }

    public interface Gear {
    }

    @Bean
    @io.micronaut.core.annotation.Order(20)
    public static class SlowGear implements Gear {
    }

    @Bean
    @io.micronaut.core.annotation.Order(10)
    public static class FastGear implements Gear {
    }

    public interface Lever {
    }

    @Bean
    @Primary
    @io.micronaut.core.annotation.Order(5)
    public static class LongLever implements Lever {
    }

    @Bean
    @Primary
    @io.micronaut.core.annotation.Order(1)
    public static class ShortLever implements Lever {
    }

    @Bean
    @io.micronaut.core.annotation.Order(0)
    public static class SpareLever implements Lever {
    }

    public static class Meter {
    }

    @io.micronaut.context.annotation.Context
    public static class PlainMeter extends Meter {
    }

    @Bean
    public static class Drawer {
        @Inject
        Shape shape;
    }

    @Dependent
    public static class Injected {
        @SuppressWarnings("rawtypes")
        @Inject
        Holder holder;
        @Inject
        Instance<Box> boxes;
        @Inject
        Service service;
        @Inject
        @org.jspecify.annotations.Nullable
        IndexMarker indexed;
        @Inject
        Pricing pricing;
        @Inject
        Clock clock;
        @Inject
        Engine engine;
        @Inject
        Shape shape;
        @Inject
        Figure figure;
        @Inject
        Meter meter;
        @Inject
        Gear gear;
        @Inject
        Lever lever;
    }

    @Test
    void aRawLookupOfAParameterizedBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Injected injected = context.getBean(Injected.class);
            // a bean of Holder<T> is a bean of the raw Holder, a variable saying nothing
            assertInstanceOf(Holder.class, injected.holder);
            assertInstanceOf(Holder.class, container.createInstance().select(Holder.class).get());
            assertEquals(Set.of(Holder.class), beanClasses(container, Holder.class));
            // a bean of Box<String> is not one of the raw Box
            assertTrue(injected.boxes.isUnsatisfied());
            assertTrue(container.createInstance().select(Box.class).isUnsatisfied());
            assertEquals(Set.of(), beanClasses(container, Box.class));
        }
    }

    @Test
    void aReplacedBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertInstanceOf(ReplacingService.class, context.getBean(Injected.class).service);
            assertInstanceOf(ReplacingService.class, container.createInstance().select(Service.class).get());
            assertEquals(Set.of(ReplacingService.class), beanClasses(container, Service.class));
            assertEquals(ReplacingService.class, container.resolve(container.getBeans(Service.class)).getBeanClass());
        }
    }

    @Test
    void aDefinitionOnlyIndexedUnderAType() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertNull(context.getBean(Injected.class).indexed);
            assertTrue(container.createInstance().select(IndexMarker.class).isUnsatisfied());
            assertEquals(Set.of(), beanClasses(container, IndexMarker.class));
        }
    }

    @Test
    void anUnselectedAlternative() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertInstanceOf(StandardPricing.class, context.getBean(Injected.class).pricing);
            assertInstanceOf(StandardPricing.class, container.createInstance().select(Pricing.class).get());
            assertEquals(Set.of(StandardPricing.class), beanClasses(container, Pricing.class));
        }
    }

    @Test
    void aBeanOfTheSpecificationInjectedWithABeanOfMicronautsOwn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Injected injected = context.getBean(Injected.class);
            assertInstanceOf(PlainClock.class, injected.clock);
            assertInstanceOf(PlainClock.class, container.createInstance().select(Clock.class).get());
            assertEquals(Set.of(PlainClock.class), beanClasses(container, Clock.class));
            // two beans of Micronaut's own: its rules choose the primary one, wherever the bean is resolved
            assertInstanceOf(MainEngine.class, injected.engine);
            assertInstanceOf(MainEngine.class, container.createInstance().select(Engine.class).get());
            assertEquals(MainEngine.class, container.resolve(container.getBeans(Engine.class)).getBeanClass());
        }
    }

    @Test
    void aBeanOfMicronautsOwnInjectedWithATypeTwoBeansOfTheSpecificationHave() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            // the one with the default qualifier, which Micronaut's own resolution finds as the primary one
            assertInstanceOf(Circle.class, context.getBean(Drawer.class).shape);
            assertInstanceOf(Circle.class, context.getBean(Injected.class).shape);
            assertInstanceOf(Circle.class, container.createInstance().select(Shape.class).get());
            assertEquals(Set.of(Circle.class), beanClasses(container, Shape.class));
        }
    }

    @Test
    void aBeanOfTheSpecificationNoQualifierAdmitsIsNotReadmittedByMicronautsRules() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            // the bean of the specification is @Fancy, so the point is resolved among the beans of Micronaut's own
            // alone, which the disqualified one is not among
            assertInstanceOf(PlainFigure.class, context.getBean(Injected.class).figure);
            assertInstanceOf(PlainFigure.class, container.createInstance().select(Figure.class).get());
            assertEquals(Set.of(PlainFigure.class), beanClasses(container, Figure.class));
            assertEquals(PlainFigure.class, container.resolve(container.getBeans(Figure.class)).getBeanClass());
            // a shared bean of Micronaut's own resolved by Micronaut's rules is the instance its scope holds
            assertSame(context.getBean(PlainMeter.class), context.getBean(Injected.class).meter);
        }
    }

    @Test
    void beansOfMicronautsOwnAreResolvedByMicronautsWholePrecedenceWhereverTheyAreResolved() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Injected injected = context.getBean(Injected.class);
            // no primary one: the lowest order
            assertInstanceOf(FastGear.class, injected.gear);
            assertInstanceOf(FastGear.class, container.createInstance().select(Gear.class).get());
            assertEquals(FastGear.class, container.resolve(container.getBeans(Gear.class)).getBeanClass());
            // several primary ones: the lowest order among them, not among every candidate
            assertInstanceOf(ShortLever.class, injected.lever);
            assertInstanceOf(ShortLever.class, container.createInstance().select(Lever.class).get());
            assertEquals(ShortLever.class, container.resolve(container.getBeans(Lever.class)).getBeanClass());
        }
    }

    private static Set<Class<?>> beanClasses(CdiBeanContainer container, Class<?> type) {
        Set<Class<?>> classes = new java.util.HashSet<>();
        for (jakarta.enterprise.inject.spi.Bean<?> bean : container.getBeans(type)) {
            classes.add(bean.getBeanClass());
        }
        return classes;
    }
}
