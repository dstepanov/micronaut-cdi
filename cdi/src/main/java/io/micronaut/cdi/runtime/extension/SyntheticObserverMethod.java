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

import io.micronaut.cdi.annotation.CdiSyntheticObserver;
import io.micronaut.cdi.annotation.CdiSyntheticParameter;
import io.micronaut.cdi.runtime.CdiAnnotations;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
    private final BeanDefinition<?> definition;
    private final AnnotationValue<CdiSyntheticObserver> record;
    private final Type eventType;
    private final CdiParameters parameters;
    private volatile @Nullable Set<Annotation> qualifiers;

    SyntheticObserverMethod(BeanContext beanContext, BeanDefinition<?> definition,
                            AnnotationValue<CdiSyntheticObserver> record) {
        this.beanContext = beanContext;
        this.definition = definition;
        this.record = record;
        this.eventType = RecordedTypes.typeOf(record.getAnnotation(CdiSyntheticObserver.EVENT_TYPE).orElseThrow());
        this.parameters = new CdiParameters(record.getAnnotations("params", CdiSyntheticParameter.class));
    }

    @Override
    public Class<?> getBeanClass() {
        return definition.getBeanType();
    }

    @Override
    public Type getObservedType() {
        return eventType;
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        Set<Annotation> resolved = qualifiers;
        if (resolved == null) {
            // a qualifier is recorded as the values it was written with, and is an annotation instance only
            // on this side of the specification's interface
            List<AnnotationValue<Annotation>> recorded = record.getAnnotations(CdiSyntheticObserver.QUALIFIERS);
            Class<?>[] types = record.classValues("qualifierTypes");
            Set<Annotation> instances = new LinkedHashSet<>();
            for (int i = 0; i < recorded.size() && i < types.length; i++) {
                instances.add(CdiAnnotations.annotationOf(annotationType(types[i]), recorded.get(i)));
            }
            resolved = java.util.Collections.unmodifiableSet(instances);
            qualifiers = resolved;
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotationType(Class<?> type) {
        return (Class<? extends Annotation>) type;
    }

    @Override
    public Reception getReception() {
        return Reception.ALWAYS;
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return switch (record.stringValue("transactionPhase").orElse("IN_PROGRESS")) {
            case "BEFORE_COMPLETION" -> TransactionPhase.BEFORE_COMPLETION;
            case "AFTER_COMPLETION" -> TransactionPhase.AFTER_COMPLETION;
            case "AFTER_FAILURE" -> TransactionPhase.AFTER_FAILURE;
            case "AFTER_SUCCESS" -> TransactionPhase.AFTER_SUCCESS;
            default -> TransactionPhase.IN_PROGRESS;
        };
    }

    @Override
    public int getPriority() {
        return record.intValue("priority").orElse(jakarta.interceptor.Interceptor.Priority.APPLICATION + 500);
    }

    @Override
    public boolean isAsync() {
        return record.booleanValue("async").orElse(false);
    }

    @Override
    public void notify(T event) {
        notify(event, new Metadata(getObservedQualifiers(), eventType));
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
            observer.observe(new Context<>(event, metadata), parameters);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.event.ObserverException(e.getMessage(), e);
        }
    }

    /**
     * The observer, from the definition the compiler generated for the class the extension named.
     */
    @SuppressWarnings("unchecked")
    private SyntheticObserver<T> instantiate() {
        return (SyntheticObserver<T>) beanContext.getBean(definition);
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
