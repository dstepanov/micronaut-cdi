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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

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
public final class CdiEvent<T> implements MicronautEvent<T> {

    private final ObserverRegistry registry;
    private final Argument<?> type;
    private final List<CdiQualifier> qualifiers;
    /**
     * Whether the type is the type of the events fired, stated in full by whoever selected it as an
     * {@code Argument}: nothing is then derived from the class of the event object.
     */
    private final boolean exact;
    private final @Nullable InjectionPoint injectedAt;

    public CdiEvent(ObserverRegistry registry, Argument<T> type, Set<Annotation> qualifiers) {
        this(registry, type, CdiQualifier.ofInstances(registry.types(), qualifiers), null);
    }

    public CdiEvent(ObserverRegistry registry, Type type, Set<Annotation> qualifiers,
                    @Nullable InjectionPoint injectedAt) {
        this(registry, SpecificationTypes.argumentOf(type),
            CdiQualifier.ofInstances(registry.types(), qualifiers), injectedAt);
    }

    CdiEvent(ObserverRegistry registry, Argument<?> type, List<CdiQualifier> qualifiers,
             @Nullable InjectionPoint injectedAt) {
        this(registry, type, qualifiers, injectedAt, false);
    }

    private CdiEvent(ObserverRegistry registry, Argument<?> type, List<CdiQualifier> qualifiers,
                     @Nullable InjectionPoint injectedAt,
                     boolean exact) {
        this.exact = exact;
        this.registry = registry;
        this.type = type;
        this.qualifiers = qualifiers;
        this.injectedAt = injectedAt;
    }

    @Override
    public void fire(T event) {
        CdiTypes.EventType eventType = eventTypeOf(event);
        registry.notifyObservers(event, eventType.type(), eventType.above(), qualifiers, false, injectedAt);
    }

    /**
     * The type of the event: the type stated in full where it was, and otherwise the class of the event object
     * with its type variables resolved from the type the event is fired as (section 2.8.1). An event object whose
     * runtime class declares type variables the event's own type does not resolve has no event types, and firing
     * it is an error: resolving is what checks it.
     */
    private CdiTypes.EventType eventTypeOf(Object event) {
        return exact ? new CdiTypes.EventType(type, null)
            : CdiTypes.eventTypeAndClosureOf(registry.typeIndex(), event.getClass(), type);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        return fireAsync(event, NotificationOptions.ofExecutor(registry.asyncExecutor()));
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        CdiTypes.EventType eventType = eventTypeOf(event);
        Executor executor = options.getExecutor();
        return CompletableFuture.supplyAsync(() -> {
            // every asynchronous observer is notified, and what any of them threw arrives together, as the
            // suppressed exceptions of one completion failure (section 2.8.5)
            List<Throwable> thrown = registry.notifyObserversCollecting(
                event, eventType.type(), eventType.above(), qualifiers, injectedAt);
            if (!thrown.isEmpty()) {
                CompletionException failure =
                    new CompletionException(thrown.get(0));
                for (Throwable each : thrown) {
                    failure.addSuppressed(each);
                }
                throw failure;
            }
            return event;
        }, executor == null ? registry.asyncExecutor() : executor);
    }

    @Override
    public MicronautEvent<T> select(Annotation... qualifiers) {
        // the type stays what it was selected as: a selection of qualifiers says nothing of the type
        return new CdiEvent<>(registry, type, and(CdiQualifier.ofInstances(registry.types(), qualifiers)), injectedAt, exact);
    }

    @Override
    public <U extends T> MicronautEvent<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiEvent<>(registry, Argument.of(subtype), and(CdiQualifier.ofInstances(registry.types(), qualifiers)), injectedAt);
    }

    @Override
    public <U extends T> MicronautEvent<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        Argument<?> selected = SpecificationTypes.argumentOf(subtype.getType());
        requireNoTypeVariable(selected);
        return new CdiEvent<>(registry, selected, and(CdiQualifier.ofInstances(registry.types(), qualifiers)), injectedAt);
    }

    @Override
    public MicronautEvent<T> select(AnnotationValue<?> qualifier,
                                    AnnotationValue<?>... qualifiers) {
        return new CdiEvent<>(registry, type, and(CdiInstance.valuesOf(registry.types(), qualifier, qualifiers)), injectedAt, exact);
    }

    @Override
    public <U extends T> MicronautEvent<U> select(
        Class<U> subtype, AnnotationValue<?> qualifier,
        AnnotationValue<?>... qualifiers) {
        return new CdiEvent<>(registry, Argument.of(subtype), and(CdiInstance.valuesOf(registry.types(), qualifier, qualifiers)),
            injectedAt);
    }

    @Override
    public <U extends T> MicronautEvent<U> select(
        Argument<U> subtype, AnnotationValue<?>... qualifiers) {
        requireNoTypeVariable(subtype);
        return new CdiEvent<>(registry, subtype, and(CdiInstance.valuesOf(registry.types(), null, qualifiers)), injectedAt, true);
    }

    private static void requireNoTypeVariable(Argument<?> selected) {
        if (selected.isUnresolvedTypeVariable()) {
            throw new IllegalArgumentException("An event cannot be selected as a type variable");
        }
        if (CdiTypes.isParameterized(selected)) {
            for (Argument<?> argument : selected.getTypeParameters()) {
                requireNoTypeVariable(argument);
            }
        }
    }

    private List<CdiQualifier> and(List<CdiQualifier> more) {
        CdiQualifier.requireWellFormed(registry.types(), qualifiers, more);
        List<CdiQualifier> all = new ArrayList<>(qualifiers);
        for (CdiQualifier qualifier : more) {
            if (!CdiQualifier.isRetainedAtRuntime(registry.types(), qualifier)) {
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
