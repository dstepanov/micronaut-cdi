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
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
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
public final class CdiEvent<T> implements Event<T> {

    private final ObserverRegistry registry;
    private final java.lang.reflect.Type type;
    private final Set<Annotation> qualifiers;
    private final jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt;

    public CdiEvent(ObserverRegistry registry, Argument<T> type, Set<Annotation> qualifiers) {
        this(registry, CdiTypes.requiredTypeOf(type), qualifiers, null);
    }

    public CdiEvent(ObserverRegistry registry, java.lang.reflect.Type type, Set<Annotation> qualifiers,
                    jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt) {
        this.registry = registry;
        this.type = type;
        this.qualifiers = qualifiers;
        this.injectedAt = injectedAt;
    }

    @Override
    public void fire(T event) {
        requireResolvableType(event);
        registry.notifyObservers(event, type, qualifiers, false, injectedAt);
    }

    /**
     * Section 2.8.1: an event object whose runtime class declares type variables the event's own type does not
     * resolve has no event types, and firing it is an error. Resolving is what checks it.
     */
    private void requireResolvableType(T event) {
        CdiTypes.eventTypeOf(event.getClass(), type);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        return fireAsync(event, NotificationOptions.ofExecutor(ForkJoinPool.commonPool()));
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        requireResolvableType(event);
        Executor executor = options.getExecutor();
        return CompletableFuture.supplyAsync(() -> {
            // every asynchronous observer is notified, and what any of them threw arrives together, as the
            // suppressed exceptions of one completion failure (section 2.8.5)
            java.util.List<Throwable> thrown = registry.notifyObserversCollecting(
                event, type, qualifiers, injectedAt);
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
    public Event<T> select(Annotation... qualifiers) {
        return new CdiEvent<>(registry, type, and(qualifiers), injectedAt);
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiEvent<>(registry, subtype, and(qualifiers), injectedAt);
    }

    @Override
    public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        requireNoTypeVariable(subtype.getType());
        return new CdiEvent<>(registry, subtype.getType(), and(qualifiers), injectedAt);
    }

    private static void requireNoTypeVariable(java.lang.reflect.Type selected) {
        if (selected instanceof java.lang.reflect.TypeVariable<?>) {
            throw new IllegalArgumentException("An event cannot be selected as a type variable");
        }
        if (selected instanceof java.lang.reflect.ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                requireNoTypeVariable(argument);
            }
        }
    }

    private Set<Annotation> and(Annotation... more) {
        Set<Annotation> all = new LinkedHashSet<>(qualifiers);
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        for (Annotation qualifier : qualifiers) {
            seen.add(qualifier.annotationType());
        }
        for (Annotation qualifier : more) {
            Class<? extends Annotation> qualifierType = qualifier.annotationType();
            if (!ExtensionQualifiers.isQualifier(qualifierType)) {
                throw new IllegalArgumentException(qualifierType.getName() + " is not a qualifier");
            }
            java.lang.annotation.Retention retention =
                qualifierType.getAnnotation(java.lang.annotation.Retention.class);
            if (retention == null || retention.value() != java.lang.annotation.RetentionPolicy.RUNTIME) {
                throw new IllegalArgumentException("The qualifier " + qualifierType.getName()
                    + " is not retained at runtime, and cannot qualify an event");
            }
            if (!seen.add(qualifierType)
                && !qualifierType.isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                throw new IllegalArgumentException("The qualifier " + qualifierType.getName()
                    + " is given twice");
            }
            if (!(qualifier instanceof Any)) {
                all.add(qualifier);
            }
        }
        return all;
    }
}
