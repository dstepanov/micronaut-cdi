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
import io.micronaut.cdi.internal.runtime.CdiInstance;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * A dependent instance is destroyed once, by whichever of the lookup and the handle gets to it first.
 *
 * <p>Section 5.1.1 makes a dependent instance obtained through a programmatic lookup a dependent object of that
 * lookup, destroyed when the lookup is. Section 5.1.2 lets the handle destroy it sooner. Doing both must not run
 * the bean's {@code @PreDestroy} twice, and a dependent instance is a fresh one for every lookup.</p>
 */
class DependentDestructionTest {

    @BeforeEach
    void clear() {
        Flare.DESTROYED.set(0);
    }

    @Test
    void aDependentDestroyedThroughItsHandleIsNotDestroyedAgainWithTheLookup() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            try (CdiInstance<Flare> lookup =
                     (CdiInstance<Flare>) container.createInstance().select(Flare.class)) {
                Instance.Handle<Flare> handle = lookup.getHandle();
                handle.get();
                handle.destroy();
                assertEquals(1, Flare.DESTROYED.get());
                // destroying the same handle again destroys nothing
                handle.destroy();
                assertEquals(1, Flare.DESTROYED.get());
            }
            // and closing the lookup it belonged to does not destroy it a third time
            assertEquals(1, Flare.DESTROYED.get());
        }
    }

    @Test
    void aDependentNotDestroyedThroughItsHandleGoesWithTheLookup() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            CdiInstance<Flare> lookup = (CdiInstance<Flare>) container.createInstance().select(Flare.class);
            lookup.get();
            assertEquals(0, Flare.DESTROYED.get());
            lookup.close();
            assertEquals(1, Flare.DESTROYED.get());
        }
    }

    @Test
    void everyInjectionPointGetsADependentInstanceOfItsOwn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Rocket rocket = container.createInstance().select(Rocket.class).get();
            assertNotSame(rocket.first, rocket.second);
        }
    }

    /**
     * A dependent bean that counts how often it was destroyed.
     */
    @Dependent
    public static class Flare {

        /**
         * How often a @PreDestroy of this bean has run.
         */
        public static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void spent() {
            DESTROYED.incrementAndGet();
        }
    }

    /**
     * Two injection points of the same dependent bean, which are two instances.
     */
    @Singleton
    public static class Rocket {

        @Inject
        Flare first;

        @Inject
        Flare second;
    }

    @Test
    void aLookupUsedInTheConstructorOfItsOwnerCreatesWhatGoesWithTheOwner() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance.Handle<Launcher> handle = container.createInstance().select(Launcher.class).getHandle();
            Launcher launcher = handle.get();
            assertEquals(0, Flare.DESTROYED.get());
            assertEquals(Flare.class, launcher.flare.getClass());
            handle.destroy();
            assertEquals(1, Flare.DESTROYED.get());
        }
    }

    @Test
    void theDependentsOfALookupGoTheLastCreatedFirstEveryOneOfThemThoughOneFails() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance.Handle<Salvo> handle = container.createInstance().select(Salvo.class).getHandle();
            Salvo salvo = handle.get();
            salvo.fire(3);
            Spark.SPENT.clear();
            // a failing @PreDestroy is reported rather than thrown, and stops none of the others
            handle.destroy();
            assertEquals(java.util.List.of(3, 2, 1), Spark.SPENT);
        }
    }

    /**
     * Looks up a dependent bean as it is constructed, through the lookup it is constructed with.
     */
    @Dependent
    public static class Launcher {

        final Flare flare;

        @Inject
        Launcher(Instance<Flare> flares) {
            this.flare = flares.get();
        }
    }

    /**
     * A dependent bean that records the order it is destroyed in, and fails as the second one is.
     */
    @Dependent
    public static class Spark {

        static final java.util.List<Integer> SPENT = new java.util.concurrent.CopyOnWriteArrayList<>();
        private static final AtomicInteger CREATED = new AtomicInteger();

        int number;

        void number(int number) {
            this.number = number;
        }

        @PreDestroy
        void spent() {
            SPENT.add(number);
            if (number == 2) {
                throw new IllegalStateException("the second spark fails as it is destroyed");
            }
        }
    }

    /**
     * Looks up a number of dependent beans after it is constructed.
     */
    @Dependent
    public static class Salvo {

        @Inject
        Instance<Spark> sparks;

        void fire(int count) {
            for (int i = 1; i <= count; i++) {
                sparks.get().number(i);
            }
        }
    }
}
