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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Fires the events of the container's own lifecycle, which section 2.8.6 has an application observe.
 *
 * <p>They mark the two moments an application has to be able to see: the container is ready, and the container is
 * about to stop. Both are moments Micronaut has of its own — the bean context has started, and is closing — so
 * what is left is to fire the events of the specification at them.</p>
 *
 * <p>The bean is created eagerly rather than when something asks for it, because the moment it exists is the
 * moment it fires the first of those events.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@io.micronaut.context.annotation.Context
@Internal
public final class ContainerLifecycle {

    private final ObserverRegistry observers;

    public ContainerLifecycle(ObserverRegistry observers) {
        this.observers = observers;
    }

    @PostConstruct
    void started() {
        // the order of section 2.9: the application context is initialized first, and Startup follows
        fire(new Object(), Object.class, Set.of(Initialized.Literal.of(ApplicationScoped.class)));
        fire(new Startup(), Startup.class, Set.of());
    }

    @PreDestroy
    void stopping() {
        // and the mirror on the way down: Shutdown first, then the application context says it is going
        fire(new Shutdown(), Shutdown.class, Set.of());
        fire(new Object(), Object.class, Set.of(BeforeDestroyed.Literal.of(ApplicationScoped.class)));
        fire(new Object(), Object.class, Set.of(Destroyed.Literal.of(ApplicationScoped.class)));
    }

    private void fire(Object event, Class<?> type, Set<Annotation> qualifiers) {
        observers.notifyObservers(event, Argument.of(type), qualifiers, false);
    }
}
