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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An observer an extension synthesised (section 2.10.5): notified like any other, it creates an instance of the
 * class the extension named and hands it the event and the parameters the extension left.
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class SyntheticObserverMethod<T> implements ObserverMethod<T>,
    io.micronaut.cdi.runtime.CdiNotifiable {

    private final BeanContext beanContext;
    private final SyntheticObserverDescription<T> description;

    SyntheticObserverMethod(BeanContext beanContext, SyntheticObserverDescription<T> description) {
        this.beanContext = beanContext;
        this.description = description;
    }

    @Override
    public Class<?> getBeanClass() {
        return description.observer();
    }

    @Override
    public Type getObservedType() {
        return description.eventType();
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return new LinkedHashSet<>(description.qualifiers());
    }

    @Override
    public Reception getReception() {
        return Reception.ALWAYS;
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return description.transactionPhase();
    }

    @Override
    public int getPriority() {
        return description.priority();
    }

    @Override
    public boolean isAsync() {
        return description.async();
    }

    @Override
    public void notify(T event) {
        notify(event, new Metadata(new LinkedHashSet<>(description.qualifiers()), description.eventType()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void notifyWith(Object event, EventMetadata metadata) {
        notify((T) event, metadata);
    }

    /**
     * Notifies the observer with the metadata of the firing.
     *
     * @param event    The event
     * @param metadata The metadata
     */
    public void notify(T event, EventMetadata metadata) {
        SyntheticObserver<T> observer = instantiate();
        try {
            observer.observe(new Context<>(event, metadata), new CdiParameters(description.parameters()));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.event.ObserverException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private SyntheticObserver<T> instantiate() {
        java.util.Optional<? extends SyntheticObserver<T>> bean =
            (java.util.Optional<? extends SyntheticObserver<T>>) beanContext
                .findBean(io.micronaut.core.type.Argument.of(description.observer()));
        if (bean.isPresent()) {
            return bean.get();
        }
        try {
            return description.observer().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The synthetic observer " + description.observer()
                + " could not be created", e);
        }
    }

    private record Metadata(Set<Annotation> qualifiers, Type type) implements EventMetadata {

        @Override
        public Set<Annotation> getQualifiers() {
            return qualifiers;
        }

        @Override
        public jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint getInjectionPoint() {
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
