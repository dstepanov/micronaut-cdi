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

import io.micronaut.cdi.annotation.CdiObserver;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The observer methods of every bean in the container, and the resolution of the ones an event notifies.
 *
 * <p>Which methods those are was decided while they were compiled: the annotation processor marked each of them,
 * and Micronaut generated an executable method for it. All that is left is to find the marked ones, which is a
 * walk over the bean definitions rather than a scan of the classpath, and to keep them so the walk happens
 * once.</p>
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
    private final List<ObserverMethod<?>> synthetic = new ArrayList<>();
    private volatile @Nullable List<ObserverMethod<?>> observers;

    public ObserverRegistry(BeanContext beanContext) {
        this.beanContext = beanContext;
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
        synthetic.add(observer);
        observers = null;
    }

    private List<ObserverMethod<?>> find() {
        List<ObserverMethod<?>> found = new ArrayList<>(synthetic);
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
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
    public List<ObserverMethod<?>> resolve(Type eventType, Set<Annotation> eventQualifiers, boolean async) {
        List<ObserverMethod<?>> notified = new ArrayList<>();
        for (ObserverMethod<?> observer : observers()) {
            if (observer.isAsync() != async) {
                continue;
            }
            if (CdiAssignability.isMatchingEvent(eventType, eventQualifiers, observer.getObservedType(),
                observer.getObservedQualifiers())) {
                notified.add(observer);
            }
        }
        return notified;
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
        notifyObservers(event, CdiTypes.typeOf(declaredType), eventQualifiers, async, null);
    }

    /**
     * Notifies the observer methods an event notifies, in order, telling each where the event came from.
     *
     * @param event           The event
     * @param declaredType    The type the event was declared as
     * @param eventQualifiers The qualifiers the event was fired with
     * @param async           Whether the event was fired asynchronously
     * @param firedFrom       The injection point the event was fired through, when it was fired through one
     */
    @SuppressWarnings("unchecked")
    public void notifyObservers(Object event,
                                Type declaredType,
                                Set<Annotation> eventQualifiers,
                                boolean async,
                                jakarta.enterprise.inject.spi.@io.micronaut.core.annotation.Nullable
                                    InjectionPoint firedFrom) {
        Type eventType = CdiTypes.eventTypeOf(event.getClass(), declaredType);
        jakarta.enterprise.inject.spi.EventMetadata metadata =
            new CdiEventMetadata(eventQualifiers, firedFrom, eventType);
        for (ObserverMethod<?> observer : resolve(eventType, eventQualifiers, async)) {
            ((CdiNotifiable) observer).notifyWith(event, metadata);
        }
    }

    /**
     * Notifies every asynchronous observer the event resolves, letting none of them stop the others: what each
     * threw is collected and returned together.
     *
     * @param event           The event
     * @param declaredType    The type the event was declared as
     * @param eventQualifiers The qualifiers the event was fired with
     * @param firedFrom       The injection point the event was fired through, when it was fired through one
     * @return What the observers threw, in notification order
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Throwable> notifyObserversCollecting(
        Object event, Type declaredType, Set<Annotation> eventQualifiers,
        jakarta.enterprise.inject.spi.@io.micronaut.core.annotation.Nullable InjectionPoint firedFrom) {
        Type eventType = CdiTypes.eventTypeOf(event.getClass(), declaredType);
        jakarta.enterprise.inject.spi.EventMetadata metadata =
            new CdiEventMetadata(eventQualifiers, firedFrom, eventType);
        java.util.List<Throwable> thrown = new java.util.ArrayList<>(0);
        io.micronaut.cdi.context.RequestScope requestScope =
            beanContext.findBean(io.micronaut.cdi.context.RequestScope.class).orElse(null);
        for (ObserverMethod<?> observer : resolve(eventType, eventQualifiers, true)) {
            try {
                if (requestScope == null) {
                    ((CdiNotifiable) observer).notifyWith(event, metadata);
                } else {
                    // section 2.5.6: the request context is active during the notification of an asynchronous
                    // observer method, one span around each invocation — quietly, the way the footing under a
                    // @PostConstruct callback is, so that no lifecycle event the application did not ask for
                    // reaches its observers
                    requestScope.duringCreation(() -> {
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

    /**
     * The type an event is resolved by.
     *
     * <p>It is the runtime class of the event, which is what section 2.8.1 says, except where the type it was
     * declared as is parameterized: the parameterization is not on the object itself and would be lost.</p>
     */

}
