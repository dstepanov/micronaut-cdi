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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The finer points of the {@code Instance} contract: what a handle destroyed before it resolved does, what a
 * second iteration of {@code handles()} hands out, what iteration leaves behind, and how exact a
 * {@code getReference} type has to be.
 */
class InstanceHandleSemanticsTest {

    @Test
    void destroyingAnUnresolvedHandleIsANoOpThatLeavesItUsable() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance.Handle<Widget> handle = container.createInstance().select(Widget.class).getHandle();
            // the specification has this do nothing: no reference was ever obtained
            handle.destroy();
            assertNotNull(handle.get());
        }
    }

    @Test
    void aHandleOnANullDependentProductCreatesItOnceAndDestroysItsDependents() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            int producedBefore = AbsentPartProducer.PRODUCED.get();
            Instance.Handle<AbsentPart> handle = container.createInstance().select(AbsentPart.class).getHandle();
            // null is a valid product of a dependent producer: obtained once, and the handle holds it
            assertNull(handle.get());
            assertNull(handle.get());
            assertEquals(producedBefore + 1, AbsentPartProducer.PRODUCED.get());
            int destroyedBefore = Bolt.DESTROYED.get();
            // the dependent injected into the producer method is a dependent of the product, null or not
            // (section 6.4.1), and goes when the handle destroys it
            handle.destroy();
            assertEquals(destroyedBefore + 1, Bolt.DESTROYED.get());
            // the reference was obtained and destroyed: the handle has nothing more to give
            assertThrows(IllegalStateException.class, handle::get);
            handle.destroy();
            assertEquals(destroyedBefore + 1, Bolt.DESTROYED.get());
        }
    }

    @Test
    void eachIterationOfHandlesIsAFreshSetOfHandles() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Iterable<? extends Instance.Handle<Widget>> handles =
                container.createInstance().select(Widget.class).handles();
            List<Instance.Handle<Widget>> first = new ArrayList<>();
            handles.forEach(first::add);
            first.forEach(handle -> {
                handle.get();
                handle.destroy();
            });
            List<Instance.Handle<Widget>> second = new ArrayList<>();
            handles.forEach(second::add);
            assertEquals(first.size(), second.size());
            for (int i = 0; i < first.size(); i++) {
                assertNotSame(first.get(i), second.get(i));
            }
            // the fresh handles resolve, untouched by the first pass's destruction
            second.forEach(handle -> assertNotNull(handle.get()));
        }
    }

    @Test
    void aDependentInstanceObtainedByIterationIsDestroyedWithTheLookup() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            int destroyedBefore = Widget.DESTROYED.get();
            Instance<Widget> widgets = container.createInstance().select(Widget.class);
            int seen = 0;
            for (Widget widget : widgets) {
                assertNotNull(widget);
                seen++;
            }
            assertEquals(1, seen);
            // letting go of the lookup lets go of what iterating it created
            ((AutoCloseable) widgets).close();
            assertEquals(destroyedBefore + 1, Widget.DESTROYED.get());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void getReferenceRejectsATypeTheBeanDoesNotHave() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.getBeans(new TypeLiteral<List<String>>() { }.getType())
                .iterator().next();
            // the parameterization is part of the type: a List<String> bean is not a List<Integer>
            assertThrows(IllegalArgumentException.class, () -> container.getReference(bean,
                new TypeLiteral<List<Integer>>() { }.getType(), container.createCreationalContext(null)));
            assertNotNull(container.getReference(bean,
                new TypeLiteral<List<String>>() { }.getType(), container.createCreationalContext(null)));
        }
    }

    /**
     * A dependent bean that counts its destructions.
     */
    @Dependent
    public static class Widget {

        /**
         * How many widgets have been destroyed.
         */
        public static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void done() {
            DESTROYED.incrementAndGet();
        }
    }

    /**
     * A type a dependent producer produces as null.
     */
    public static class AbsentPart {
    }

    /**
     * A dependent bean injected into the producer of the absent part, which counts its destructions.
     */
    @Dependent
    public static class Bolt {

        /**
         * How many bolts have been destroyed.
         */
        public static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void done() {
            DESTROYED.incrementAndGet();
        }
    }

    /**
     * Produces the absent part as null, counting how often it is asked to.
     */
    @Dependent
    public static class AbsentPartProducer {

        /**
         * How many times the producer ran.
         */
        public static final AtomicInteger PRODUCED = new AtomicInteger();

        @jakarta.enterprise.inject.Produces
        @Dependent
        AbsentPart absent(Bolt bolt) {
            PRODUCED.incrementAndGet();
            return null;
        }
    }

    /**
     * Produces the parameterized bean the reference test asks for by the wrong parameterization.
     */
    @Dependent
    public static class WordsFactory {

        @jakarta.enterprise.inject.Produces
        @Dependent
        List<String> words() {
            return List.of("some", "words");
        }
    }
}
