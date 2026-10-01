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
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** CDI 4.1 section 4.2: observer inheritance is suppressed only by an actual overriding method. */
class PrivateObserverInheritanceTest {

    record ShadowedEvent() { }
    record TwoObserversEvent() { }
    record OverriddenEvent() { }

    public abstract static class PrivateBase {
        static int calls;

        private void observe(@Observes ShadowedEvent event) {
            calls++;
        }
    }

    @Dependent
    public static class PrivateShadow extends PrivateBase {
        static int calls;

        private void observe(ShadowedEvent event) {
            calls++;
        }
    }

    public abstract static class TwoObserversBase {
        static int calls;

        private void observe(@Observes TwoObserversEvent event) {
            calls++;
        }
    }

    @Dependent
    public static class TwoObservers extends TwoObserversBase {
        static int calls;

        private void observe(@Observes TwoObserversEvent event) {
            calls++;
        }
    }

    public abstract static class OverriddenBase {
        static int calls;

        protected void observe(@Observes OverriddenEvent event) {
            calls++;
        }
    }

    @Dependent
    public static class OverridingNonObserver extends OverriddenBase {
        static int calls;

        @Override
        protected void observe(OverriddenEvent event) {
            calls++;
        }
    }

    @Test
    void privateSameSignatureMethodDoesNotReplaceInheritedObserverBody() {
        PrivateBase.calls = 0;
        PrivateShadow.calls = 0;
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(BeanManager.class).getEvent().select(ShadowedEvent.class).fire(new ShadowedEvent());
            assertEquals(1, PrivateBase.calls);
            assertEquals(0, PrivateShadow.calls);
        }
    }

    @Test
    void bothPrivateObserverBodiesAreInvoked() {
        TwoObserversBase.calls = 0;
        TwoObservers.calls = 0;
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(BeanManager.class).getEvent().select(TwoObserversEvent.class).fire(new TwoObserversEvent());
            assertEquals(1, TwoObserversBase.calls);
            assertEquals(1, TwoObservers.calls);
        }
    }

    @Test
    void realOverrideWithoutObservesSuppressesTheInheritedObserver() {
        OverriddenBase.calls = 0;
        OverridingNonObserver.calls = 0;
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(BeanManager.class).getEvent().select(OverriddenEvent.class).fire(new OverriddenEvent());
            assertEquals(0, OverriddenBase.calls);
            assertEquals(0, OverridingNonObserver.calls);
        }
    }
}
