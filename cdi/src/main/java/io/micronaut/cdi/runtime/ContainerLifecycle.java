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

import io.micronaut.cdi.context.ApplicationScope;
import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;

import java.lang.annotation.Annotation;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The way down starts at the event Micronaut publishes as a context begins to stop, which is before it
 * destroys any bean: {@code Shutdown} is fired, then the {@code BeforeDestroyed} of the application context
 * (section 2.5.6.2), then what was obtained through the container itself is released and the application context
 * is destroyed - its beans and what depends on them - and then
 * its {@code Destroyed} is fired. The singletons, which are of a pseudo-scope and of no context, are destroyed by
 * Micronaut after that.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@io.micronaut.context.annotation.Context
@Internal
public final class ContainerLifecycle implements ApplicationEventListener<ShutdownEvent>, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerLifecycle.class);

    private final BeanContext beanContext;
    private final ObserverRegistry observers;
    private final ApplicationScope applicationScope;
    // what was obtained through the container itself and is released as it stops, see releaseAsTheContainerStops
    private final java.util.List<Runnable> releases = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ContainerLifecycle(BeanContext beanContext, ObserverRegistry observers, ApplicationScope applicationScope) {
        this.beanContext = beanContext;
        this.observers = observers;
        this.applicationScope = applicationScope;
    }

    @PostConstruct
    void started() {
        // the portable extensions an SE bootstrap asked for run first: what they add - a context, an observer,
        // a qualifier - is there by the time the application is told the container has started
        beanContext.findBean(io.micronaut.cdi.runtime.extension.PortableExtensions.Request.class)
            .ifPresent(request -> {
                io.micronaut.cdi.runtime.extension.PortableExtensions extensions = beanContext
                    .findBean(io.micronaut.cdi.runtime.extension.PortableExtensions.class).orElse(null);
                if (extensions != null) {
                    extensions.run(beanContext, request);
                } else if (request.namesExtensions()) {
                    throw new UnsupportedOperationException(
                        io.micronaut.cdi.runtime.extension.PortableExtensions.MISSING);
                }
            });
        // the order of section 2.9: the application context is initialized first, and Startup follows
        fire(new Object(), Object.class, Set.of(Initialized.Literal.of(ApplicationScoped.class)));
        fire(new Startup(), Startup.class, Set.of());
    }

    /**
     * Has something released as the container stops: the dependent instances obtained through a lookup the
     * container itself is, which an SE container is. They are released once the container has said it is
     * stopping - after {@code Shutdown} and the {@code BeforeDestroyed} of the application context, whose
     * observers still find everything in place - and before the application context is destroyed, so that what
     * their disposal uses is still there.
     *
     * @param release Releases them
     */
    public void releaseAsTheContainerStops(Runnable release) {
        releases.add(release);
    }

    @Override
    public int getOrder() {
        // after every other listener of the shutdown, which may still reach for an application scoped bean
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        if (event.getSource() != beanContext) {
            return;
        }
        // Shutdown first, then the application context says it is going. One observer throwing must not
        // silence the events after it - cleanup keyed on Destroyed still runs - nor stop the context from
        // stopping, so what an observer threw is logged, as what a bean throws as it is destroyed is
        during("Shutdown", () -> fire(new Shutdown(), Shutdown.class, Set.of()));
        during("@BeforeDestroyed(ApplicationScoped.class)",
            () -> fire(new Object(), Object.class, Set.of(BeforeDestroyed.Literal.of(ApplicationScoped.class))));
        // what was looked up through the container itself goes first, while the beans it uses are there
        for (Runnable release : releases) {
            during("the release of what was obtained through the container", release);
        }
        releases.clear();
        // the actual destruction: every application scoped bean, and the dependent objects of each
        during("the destruction of the application context", applicationScope::stop);
        during("@Destroyed(ApplicationScoped.class)",
            () -> fire(new Object(), Object.class, Set.of(Destroyed.Literal.of(ApplicationScoped.class))));
    }

    private static void during(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOG.error("The container failed during {} as it stopped", what, e);
        }
    }

    private void fire(Object event, Class<?> type, Set<Annotation> qualifiers) {
        observers.notifyObservers(event, Argument.of(type), qualifiers, false);
    }
}
