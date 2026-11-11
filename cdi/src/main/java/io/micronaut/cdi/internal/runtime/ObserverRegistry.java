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

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.cdi.internal.metadata.CdiObserver;
import io.micronaut.cdi.internal.metadata.CdiObserving;
import io.micronaut.context.BeanContext;
import io.micronaut.context.WatchableBeanContext;
import io.micronaut.context.watch.BeanDefinitionChange;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * The observer methods of every bean in the container, and the resolution of the ones an event notifies.
 *
 * <p>Which methods those are was decided while they were compiled: the annotation processor marked each of them,
 * and Micronaut generated an executable method for it. All that is left is to find the marked ones, which is a
 * walk over the bean definitions rather than a scan of the classpath. The bean context hands them over as a
 * watch of the definitions of the beans marked as observing: the ones there as the watch is registered, then again
 * whenever a definition is registered once the context runs, or retired or added by a development reload, so the
 * list follows the definitions rather than being kept from a first walk.</p>
 *
 * <p>Resolution is the rule of section 2.8.3: an observer is notified when the type it observes is one of the
 * event's types and the qualifiers it observes are among the ones the event was fired with. The ones that are
 * notified are notified in the order of their priority, lowest first, which is the order section 2.8.5 asks
 * for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ObserverRegistry {

    private final BeanContext beanContext;
    private volatile @Nullable CompiledAnnotationTypes types;
    private volatile @Nullable RecordedTypeIndex typeIndex;
    private final List<ObserverMethod<?>> synthetic = new ArrayList<>();
    /**
     * The definitions of the beans with observers, as the watch of the context last handed them, or {@code null}
     * where the context cannot be watched and they are read as they are asked for.
     */
    private volatile @Nullable Collection<BeanDefinition<Object>> observing;
    /**
     * The observer methods, sorted by priority: made again from the definitions of the watch and the synthetic
     * observers whenever either changes.
     */
    private volatile @Nullable List<ObserverMethod<?>> observers;
    private volatile @Nullable Executor asyncExecutor;

    public ObserverRegistry(BeanContext beanContext) {
        this.beanContext = beanContext;
        if (beanContext instanceof WatchableBeanContext watchable) {
            // the beans with observers are marked as they compile: the marker is read from each bean's reference.
            // The watch belongs to this bean, and is closed as it is destroyed
            watchable.definitions().stereotype(CdiObserving.class).watch(this::observingChanged);
        }
    }

    private synchronized void observingChanged(BeanDefinitionChange<Object> change) {
        observing = change.current();
        observers = null;
    }

    /**
     * What the application was compiled with of each qualifier type, which the qualifiers of an event are
     * checked and read against.
     *
     * @return The record
     */
    CompiledAnnotationTypes types() {
        CompiledAnnotationTypes compiled = types;
        if (compiled == null) {
            // a bean of the context, the same for as long as it runs: looked up once
            compiled = CompiledAnnotationTypes.of(beanContext);
            types = compiled;
        }
        return compiled;
    }

    /**
     * The executor an event fired asynchronously notifies its observers on where the firing names none: Micronaut's
     * executor of blocking work, which an observer may well do, read as it is first needed. A context without one
     * notifies them on the common pool.
     *
     * @return The executor
     */
    Executor asyncExecutor() {
        Executor found = asyncExecutor;
        if (found == null) {
            found = beanContext.findBean(ExecutorService.class,
                    Qualifiers.byName(TaskExecutors.BLOCKING))
                .<Executor>map(executor -> executor)
                .orElseGet(ForkJoinPool::commonPool);
            asyncExecutor = found;
        }
        return found;
    }

    /**
     * The generic hierarchies of the classes the application was compiled with, which event types are matched by.
     *
     * @return The index of this container
     */
    RecordedTypeIndex typeIndex() {
        RecordedTypeIndex index = typeIndex;
        if (index == null) {
            index = RecordedTypeIndex.of(beanContext);
            typeIndex = index;
        }
        return index;
    }

    /**
     * Every observer method in the container.
     *
     * @return The observer methods
     */
    public List<ObserverMethod<?>> observers() {
        List<ObserverMethod<?>> resolved = observers;
        if (resolved == null) {
            synchronized (this) {
                resolved = observers;
                if (resolved == null) {
                    resolved = find();
                    observers = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * Registers an observer an extension synthesised, which resolution and notification treat like any other.
     *
     * @param observer The observer
     */
    public synchronized void registerSynthetic(ObserverMethod<?> observer) {
        // one a program implemented itself says what it observes through the specification's interface alone
        synthetic.add(observer instanceof CdiNotifiable ? observer : new ForeignObserverMethod<>(observer, types()));
        observers = null;
    }

    private List<ObserverMethod<?>> find() {
        List<ObserverMethod<?>> found = new ArrayList<>(synthetic);
        Collection<BeanDefinition<Object>> watched = observing;
        // the typed lookup drops a replaced bean before it narrows by the marker: a replacement that observes
        // nothing still takes the observers of the bean it replaced with it
        Collection<BeanDefinition<Object>> definitions = watched != null ? watched
            : beanContext.getBeanDefinitions(Argument.OBJECT_ARGUMENT, Qualifiers.byStereotype(CdiObserving.class));
        for (BeanDefinition<?> definition : definitions) {
            for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
                AnnotationValue<CdiObserver> observer = method.getAnnotation(CdiObserver.class);
                if (observer != null) {
                    // a static observer has an executable method of its own, which is invoked without an
                    // instance of the bean that declares it
                    found.add(new CdiObserverMethod<>(beanContext, definition, method, observer));
                }
            }
        }
        found.sort(Comparator.comparingInt(ObserverMethod::getPriority));
        return List.copyOf(found);
    }

    /**
     * The observer methods an event of the given type and qualifiers notifies.
     *
     * @param eventType       The type the event was fired as
     * @param eventQualifiers The qualifiers it was fired with
     * @param async           Whether the event was fired asynchronously, which the observers of an event fired
     *                        the other way do not see
     * @return The observer methods, in the order they are notified
     */
    public List<ObserverMethod<?>> resolve(Argument<?> eventType, Collection<CdiQualifier> eventQualifiers,
                                           boolean async) {
        return resolve(eventType, null, eventQualifiers, async);
    }

    /**
     * The observer methods an event of the given type and qualifiers notifies, where the closure of its type also
     * holds the given type above it: the type it was fired as, where nothing was recorded of its class
     * ({@link CdiTypes#eventTypeAndClosureOf}).
     *
     * @param eventType       The type the event was fired as
     * @param above           The type the closure of the event type also holds, or {@code null}
     * @param eventQualifiers The qualifiers it was fired with
     * @param async           Whether the event was fired asynchronously
     * @return The observer methods, in the order they are notified
     */
    public List<ObserverMethod<?>> resolve(Argument<?> eventType, @Nullable Argument<?> above,
                                           Collection<CdiQualifier> eventQualifiers, boolean async) {
        List<ObserverMethod<?>> all = observers();
        // the closure of the event's type is the same for every observer it is matched against
        List<Argument<?>> closure = CdiAssignability.eventTypeClosureOf(typeIndex(), eventType);
        if (above != null) {
            closure = new ArrayList<>(closure);
            for (Argument<?> type : CdiAssignability.eventTypeClosureOf(typeIndex(), above)) {
                CdiTypes.addDistinct(closure, type);
            }
        }
        List<ObserverMethod<?>> notified = new ArrayList<>();
        for (ObserverMethod<?> observer : all) {
            if (observer.isAsync() != async) {
                continue;
            }
            if (CdiAssignability.isEventTypeMatching(typeIndex(), 
                ((CdiNotifiable) observer).observedArgument(), closure)
                && CdiAssignability.areEventQualifiersMatching(eventQualifiers,
                    ((CdiNotifiable) observer).observedQualifiers())) {
                notified.add(observer);
            }
        }
        return List.copyOf(notified);
    }

    /**
     * Notifies the observer methods an event notifies, in order.
     *
     * @param event           The event
     * @param declaredType    The type the event was declared as, which is what a parameterized event is resolved
     *                        by; the class of the event itself is what an event declared as a plain type is
     * @param eventQualifiers The qualifiers the event was fired with
     * @param async           Whether the event was fired asynchronously
     */
    public void notifyObservers(Object event,
                                Argument<?> declaredType,
                                Set<Annotation> eventQualifiers,
                                boolean async) {
        CdiTypes.EventType eventType = CdiTypes.eventTypeAndClosureOf(typeIndex(), event.getClass(), declaredType);
        notifyObservers(event, eventType.type(), eventType.above(), CdiQualifier.ofInstances(types(), eventQualifiers),
            async, null);
    }

    /**
     * Notifies the observer methods an event notifies, in order, telling each where the event came from.
     *
     * @param event           The event
     * @param eventType       The type of the event
     * @param eventQualifiers The qualifiers the event was fired with
     * @param async           Whether the event was fired asynchronously
     * @param firedFrom       The injection point the event was fired through, when it was fired through one
     */
    public void notifyObservers(Object event,
                                Argument<?> eventType,
                                List<CdiQualifier> eventQualifiers,
                                boolean async,
                                @Nullable
                                    InjectionPoint firedFrom) {
        notifyObservers(event, eventType, null, eventQualifiers, async, firedFrom);
    }

    /**
     * Notifies the observer methods an event notifies, in order, telling each where the event came from, where the
     * closure of its type also holds the given type above it.
     *
     * @param event           The event
     * @param eventType       The type of the event
     * @param above           The type the closure of the event type also holds, or {@code null}
     * @param eventQualifiers The qualifiers the event was fired with
     * @param async           Whether the event was fired asynchronously
     * @param firedFrom       The injection point the event was fired through, when it was fired through one
     */
    @SuppressWarnings("unchecked")
    public void notifyObservers(Object event,
                                Argument<?> eventType,
                                @Nullable Argument<?> above,
                                List<CdiQualifier> eventQualifiers,
                                boolean async,
                                @Nullable
                                    InjectionPoint firedFrom) {
        EventMetadata metadata =
            new CdiEventMetadata(beanContext, eventQualifiers, firedFrom, eventType);
        for (ObserverMethod<?> observer : resolve(eventType, above, eventQualifiers, async)) {
            ((CdiNotifiable) observer).notifyWith(event, metadata);
        }
    }

    /**
     * Notifies every asynchronous observer the event resolves, letting none of them stop the others: what each
     * threw is collected and returned together.
     *
     * @param event           The event
     * @param eventType       The type of the event
     * @param above           The type the closure of the event type also holds, or {@code null}
     * @param eventQualifiers The qualifiers the event was fired with
     * @param firedFrom       The injection point the event was fired through, when it was fired through one
     * @return What the observers threw, in notification order
     */
    @SuppressWarnings("unchecked")
    public List<Throwable> notifyObserversCollecting(
        Object event, Argument<?> eventType, @Nullable Argument<?> above, List<CdiQualifier> eventQualifiers,
        @Nullable InjectionPoint firedFrom) {
        EventMetadata metadata =
            new CdiEventMetadata(beanContext, eventQualifiers, firedFrom, eventType);
        List<Throwable> thrown = new ArrayList<>(0);
        RequestScope requestScope =
            beanContext.findBean(RequestScope.class).orElse(null);
        for (ObserverMethod<?> observer : resolve(eventType, above, eventQualifiers, true)) {
            try {
                if (requestScope == null) {
                    ((CdiNotifiable) observer).notifyWith(event, metadata);
                } else {
                    // section 2.5.6: the request context is active during the notification of an asynchronous
                    // observer method, one span around each invocation — quietly, the way the footing under a
                    // @PostConstruct callback is, so that no lifecycle event the application did not ask for
                    // reaches its observers. Section 2.8.5.3: it is a new context, and not the one of whoever
                    // fired the event, where the executor runs the notification on the firing thread
                    requestScope.inNewRequest(() -> {
                        ((CdiNotifiable) observer).notifyWith(event, metadata);
                        return null;
                    });
                }
            } catch (Throwable e) {
                thrown.add(e);
            }
        }
        return thrown;
    }
}
