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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * The event a program fires, which is the injectable half of section 2.8.
 *
 * <p>It carries the type and the qualifiers of the injection point it was injected into, and firing it notifies
 * the observers that observe them. Narrowing it with {@code select} returns another event of the narrower type
 * and the qualifiers of both, in the same way programmatic lookup narrows.</p>
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiEvent<T> implements io.micronaut.cdi.MicronautEvent<T> {

    private final ObserverRegistry registry;
    private final Argument<?> type;
    private final java.util.List<CdiQualifier> qualifiers;
    /**
     * Whether the type is the type of the events fired, stated in full by whoever selected it as an
     * {@code Argument}: nothing is then derived from the class of the event object.
     */
    private final boolean exact;
    private final jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt;

    public CdiEvent(ObserverRegistry registry, Argument<T> type, Set<Annotation> qualifiers) {
        this(registry, type, CdiQualifier.ofInstances(qualifiers), null);
    }

    public CdiEvent(ObserverRegistry registry, java.lang.reflect.Type type, Set<Annotation> qualifiers,
                    jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt) {
        this(registry, io.micronaut.cdi.runtime.type.SpecificationTypes.argumentOf(type),
            CdiQualifier.ofInstances(qualifiers), injectedAt);
    }

    CdiEvent(ObserverRegistry registry, Argument<?> type, java.util.List<CdiQualifier> qualifiers,
             jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt) {
        this(registry, type, qualifiers, injectedAt, false);
    }

    private CdiEvent(ObserverRegistry registry, Argument<?> type, java.util.List<CdiQualifier> qualifiers,
                     jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt,
                     boolean exact) {
        this.exact = exact;
        this.registry = registry;
        this.type = type;
        this.qualifiers = qualifiers;
        this.injectedAt = injectedAt;
    }

    @Override
    public void fire(T event) {
        registry.notifyObservers(event, eventTypeOf(event), qualifiers, false, injectedAt);
    }

    /**
     * Section 2.8.1: an event object whose runtime class declares type variables the event's own type does not
     * resolve has no event types, and firing it is an error. Resolving is what checks it.
     */
    /**
     * The type of the event: the type stated in full where it was, and otherwise the class of the event object
     * with its type variables resolved from the type the event is fired as (section 2.8.1).
     */
    private Argument<?> eventTypeOf(Object event) {
        return exact ? type : CdiTypes.eventTypeOf(event.getClass(), type);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        return fireAsync(event, NotificationOptions.ofExecutor(ForkJoinPool.commonPool()));
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        Argument<?> eventType = eventTypeOf(event);
        Executor executor = options.getExecutor();
        return CompletableFuture.supplyAsync(() -> {
            // every asynchronous observer is notified, and what any of them threw arrives together, as the
            // suppressed exceptions of one completion failure (section 2.8.5)
            java.util.List<Throwable> thrown = registry.notifyObserversCollecting(
                event, eventType, qualifiers, injectedAt);
            if (!thrown.isEmpty()) {
                java.util.concurrent.CompletionException failure =
                    new java.util.concurrent.CompletionException(thrown.get(0));
                for (Throwable each : thrown) {
                    failure.addSuppressed(each);
                }
                throw failure;
            }
            return event;
        }, executor == null ? ForkJoinPool.commonPool() : executor);
    }

    @Override
    public io.micronaut.cdi.MicronautEvent<T> select(Annotation... qualifiers) {
        // the type stays what it was selected as: a selection of qualifiers says nothing of the type
        return new CdiEvent<>(registry, type, and(CdiQualifier.ofInstances(qualifiers)), injectedAt, exact);
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautEvent<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiEvent<>(registry, Argument.of(subtype), and(CdiQualifier.ofInstances(qualifiers)), injectedAt);
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautEvent<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        Argument<?> selected = io.micronaut.cdi.runtime.type.SpecificationTypes.argumentOf(subtype.getType());
        requireNoTypeVariable(selected);
        return new CdiEvent<>(registry, selected, and(CdiQualifier.ofInstances(qualifiers)), injectedAt);
    }

    @Override
    public io.micronaut.cdi.MicronautEvent<T> select(io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                                     io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        return new CdiEvent<>(registry, type, and(CdiInstance.valuesOf(qualifier, qualifiers)), injectedAt, exact);
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautEvent<U> select(
        Class<U> subtype, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
        io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        return new CdiEvent<>(registry, Argument.of(subtype), and(CdiInstance.valuesOf(qualifier, qualifiers)),
            injectedAt);
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautEvent<U> select(
        Argument<U> subtype, io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        requireNoTypeVariable(subtype);
        return new CdiEvent<>(registry, subtype, and(CdiInstance.valuesOf(null, qualifiers)), injectedAt, true);
    }

    private static void requireNoTypeVariable(Argument<?> selected) {
        if (CdiTypes.isVariable(selected)) {
            throw new IllegalArgumentException("An event cannot be selected as a type variable");
        }
        if (CdiTypes.isParameterized(selected)) {
            for (Argument<?> argument : selected.getTypeParameters()) {
                requireNoTypeVariable(argument);
            }
        }
    }

    private java.util.List<CdiQualifier> and(java.util.List<CdiQualifier> more) {
        CdiQualifier.requireWellFormed(qualifiers, more);
        java.util.List<CdiQualifier> all = new java.util.ArrayList<>(qualifiers);
        for (CdiQualifier qualifier : more) {
            if (!CdiQualifier.isRetainedAtRuntime(qualifier)) {
                throw new IllegalArgumentException("The qualifier " + qualifier.name()
                    + " is not retained at runtime, and cannot qualify an event");
            }
            if (!qualifier.isAny() && !qualifier.isAmong(all)) {
                all.add(qualifier);
            }
        }
        return all;
    }
}
