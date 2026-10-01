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
import io.micronaut.cdi.test.extension.Tended;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean in a scope an extension registered declares observer and disposer methods as any other bean in a context
 * does: they are invoked on the instance its context holds, which lives on afterwards.
 *
 * <p>The observer and the disposer used to tell a dependent bean by the scopes the container serves itself, so a
 * bean in the extension's scope was taken for a dependent one and destroyed after each invocation, although its
 * context still held it.</p>
 */
class ExtensionScopedDeclaringBeanTest {

    @Test
    void anObserverIsNotifiedOnTheInstanceTheContextHolds() {
        Keeper.CREATED.set(0);
        Keeper.DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Chime.class).fire(new Chime());
            container.getEvent().select(Chime.class).fire(new Chime());

            assertEquals(2, container.createInstance().select(Keeper.class).get().heard());
            assertEquals(1, Keeper.CREATED.get());
            assertEquals(0, Keeper.DESTROYED.get());
        }
    }

    @Test
    void aDisposerIsInvokedOnTheInstanceTheContextHolds() {
        Wrapper.CREATED.set(0);
        Wrapper.DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Parcel> parcels = container.createInstance().select(Parcel.class);
            parcels.destroy(parcels.get());

            assertEquals(1, container.createInstance().select(Wrapper.class).get().disposed());
            assertEquals(1, Wrapper.CREATED.get());
            assertEquals(0, Wrapper.DESTROYED.get());
        }
    }

    /**
     * The event.
     */
    public record Chime() {
    }

    /**
     * What the producer makes.
     */
    public static class Parcel {
    }

    /**
     * Observes the event, in the extension's scope.
     */
    @Tended
    public static class Keeper {

        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        private int heard;

        @PostConstruct
        void created() {
            CREATED.incrementAndGet();
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        void hear(@Observes Chime chime) {
            heard++;
        }

        public int heard() {
            return heard;
        }
    }

    /**
     * Produces and disposes of parcels, in the extension's scope.
     */
    @Tended
    public static class Wrapper {

        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        private int disposed;

        @PostConstruct
        void created() {
            CREATED.incrementAndGet();
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        @Produces
        @Dependent
        Parcel parcel() {
            return new Parcel();
        }

        void dispose(@Disposes Parcel parcel) {
            disposed++;
        }

        public int disposed() {
            return disposed;
        }
    }
}
