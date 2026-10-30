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
package io.micronaut.cdi.test.event;

import io.micronaut.cdi.internal.runtime.ObserverRegistry;
import io.micronaut.cdi.test.extension.Dispatch;
import io.micronaut.cdi.test.extension.ReviewScenariosExtension;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code ObserverMethod.notify(EventContext)} hands the observer the metadata of the context it was given, for a
 * compiled observer method as for a synthetic observer, rather than metadata the observer makes up of itself.
 */
class NotifyWithEventContextTest {

    @Test
    void anObserverMethodIsHandedTheMetadataOfTheContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObserverRegistry container = context.getBean(ObserverRegistry.class);
            EventMetadata metadata = new GivenMetadata(Parcel.class);
            observerOf(container, Parcel.class).notify(new Context<>(new Parcel(), metadata));
            assertSame(metadata, ParcelObserver.LAST_METADATA.get());
        }
    }

    @Test
    void aSyntheticObserverIsHandedTheMetadataOfTheContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObserverRegistry container = context.getBean(ObserverRegistry.class);
            EventMetadata metadata = new GivenMetadata(Dispatch.class);
            observerOf(container, Dispatch.class).notify(new Context<>(new Dispatch("parcel"), metadata));
            assertSame(metadata, ReviewScenariosExtension.DispatchObserver.LAST_METADATA.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObserverMethod<T> observerOf(ObserverRegistry container, Class<T> type) {
        return (ObserverMethod<T>) container.observers().stream()
            .filter(observer -> observer.getObservedType() == type)
            .findFirst().orElseThrow();
    }

    /**
     * What the compiled observer observes.
     */
    public static final class Parcel {
    }

    /**
     * Keeps the metadata it was last notified with.
     */
    @Dependent
    public static class ParcelObserver {

        static final AtomicReference<EventMetadata> LAST_METADATA = new AtomicReference<>();

        void on(@Observes Parcel parcel, EventMetadata metadata) {
            LAST_METADATA.set(metadata);
        }
    }

    private record GivenMetadata(Type type) implements EventMetadata {

        @Override
        public Set<Annotation> getQualifiers() {
            return Set.of();
        }

        @Override
        public InjectionPoint getInjectionPoint() {
            return null;
        }

        @Override
        public Type getType() {
            return type;
        }
    }

    private record Context<T>(T event, EventMetadata metadata) implements EventContext<T> {

        @Override
        public T getEvent() {
            return event;
        }

        @Override
        public EventMetadata getMetadata() {
            return metadata;
        }
    }
}
