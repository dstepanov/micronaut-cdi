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
package io.micronaut.cdi.runtime;

import io.micronaut.cdi.runtime.type.SpecificationTypes;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * An observer method a program implemented itself and handed to the container, resolved and notified like the
 * ones the container made: what it observes is read once, from what it reports.
 *
 * @param <T> The observed type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ForeignObserverMethod<T> implements ObserverMethod<T>, CdiNotifiable {

    private final ObserverMethod<T> delegate;
    private final Argument<?> observed;
    private final List<CdiQualifier> qualifiers;

    ForeignObserverMethod(ObserverMethod<T> delegate) {
        this.delegate = delegate;
        this.observed = SpecificationTypes.argumentOf(delegate.getObservedType());
        this.qualifiers = CdiQualifier.ofInstances(delegate.getObservedQualifiers());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void notifyWith(Object event, EventMetadata metadata) {
        T observedEvent = (T) event;
        delegate.notify(new EventContext<>() {
            @Override
            public T getEvent() {
                return observedEvent;
            }

            @Override
            public EventMetadata getMetadata() {
                return metadata;
            }
        });
    }

    @Override
    public List<CdiQualifier> observedQualifiers() {
        return qualifiers;
    }

    @Override
    public Argument<?> observedArgument() {
        return observed;
    }

    @Override
    public Class<?> getBeanClass() {
        return delegate.getBeanClass();
    }

    @Override
    public Type getObservedType() {
        return delegate.getObservedType();
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return delegate.getObservedQualifiers();
    }

    @Override
    public Reception getReception() {
        return delegate.getReception();
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return delegate.getTransactionPhase();
    }

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public void notify(T event) {
        delegate.notify(event);
    }

    @Override
    public void notify(EventContext<T> eventContext) {
        delegate.notify(eventContext);
    }

    @Override
    public boolean isAsync() {
        return delegate.isAsync();
    }
}
