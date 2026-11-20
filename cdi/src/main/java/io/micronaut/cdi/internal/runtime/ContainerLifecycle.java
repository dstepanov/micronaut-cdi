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

import io.micronaut.aop.InterceptedProxy;
import io.micronaut.cdi.internal.context.ApplicationScope;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.cdi.internal.metadata.CdiBeanAttributes;
import io.micronaut.cdi.spi.PortableExtensions;
import io.micronaut.context.BeanContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * destroys any bean: {@code Shutdown} is fired, then the requests still open anywhere are destroyed, each told so,
 * then the {@code BeforeDestroyed} of the application context (section 2.5.6.2) is fired, then what was obtained
 * through the container itself is released and the application context is destroyed - its beans and what depends on
 * them - and then its {@code Destroyed} is fired. The singletons, which are of a pseudo-scope and of no context, are
 * destroyed by Micronaut after that. A request scoped bean that uses an application scoped bean or a singleton as it
 * is destroyed therefore finds the instance it used while the request ran, whatever order Micronaut destroys the
 * singletons in. A start that fails after the application context was announced and is rolled back takes the same
 * way down without {@code Shutdown}, which only follows {@code Startup}.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Context
@Internal
public final class ContainerLifecycle implements ApplicationEventListener<ShutdownEvent>, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerLifecycle.class);
    /**
     * The attribute of the bean context that says the application was told its application context was initialized,
     * and so is told it is destroyed. It is held on the context rather than on this bean: a start that fails in
     * {@link #started()} leaves no singleton of this class behind, and the shutdown event of its rollback is listened
     * to by a new instance, whose own flags say nothing.
     */
    private static final String APPLICATION_CONTEXT_INITIALIZED =
        ContainerLifecycle.class.getName() + ".applicationContextInitialized";

    private final BeanContext beanContext;
    private final ObserverRegistry observers;
    private final ApplicationScope applicationScope;
    private final RequestScope requestScope;
    // what was obtained through the container itself and is released as it stops, see addShutdownCallback
    private final List<Runnable> releases = new CopyOnWriteArrayList<>();
    /**
     * Whether the application was told the container started: only then is it told it shuts down. A start that
     * failed before {@code Startup} was fired is rolled back with no {@code Shutdown}, its application context
     * destroyed between its two events where it was announced ({@link #APPLICATION_CONTEXT_INITIALIZED}).
     */
    private volatile boolean started;

    public ContainerLifecycle(BeanContext beanContext, ObserverRegistry observers, ApplicationScope applicationScope,
                              RequestScope requestScope) {
        this.beanContext = beanContext;
        this.observers = observers;
        this.applicationScope = applicationScope;
        this.requestScope = requestScope;
    }

    /**
     * Whether a context has stopped, or its start failed: it does not run and is not starting either, which
     * Micronaut's own context tells apart ({@link DefaultBeanContext#isStoppingOrStopped()}). A context that is
     * stopping still runs until its stop ends, so the shutdown callbacks see it running.
     *
     * @param beanContext The context
     * @return Whether it has stopped or failed to start
     */
    public static boolean isStoppedOrFailedToStart(BeanContext beanContext) {
        return !beanContext.isRunning() && beanContext instanceof DefaultBeanContext context
            && context.isStoppingOrStopped();
    }

    @PostConstruct
    void started() {
        if (beanContext instanceof DefaultBeanContext context && context.isStoppingOrStopped()) {
            // created by the shutdown event of a context whose start failed, which is being rolled back
            // (DefaultBeanContext.stopFailedStart): nothing is run, validated or fired for a container that is
            // going down without having started
            return;
        }
        // the portable extensions an SE bootstrap asked for run first: what they add - a context, an observer,
        // a qualifier - is there by the time the application is told the container has started
        beanContext.findBean(PortableExtensions.Request.class)
            .ifPresent(request -> {
                PortableExtensions extensions = beanContext
                    .findBean(PortableExtensions.class).orElse(null);
                if (extensions != null) {
                    extensions.run(beanContext, request);
                } else if (request.namesExtensions()) {
                    throw new UnsupportedOperationException(
                        PortableExtensions.MISSING);
                }
            });
        // the deployment is validated before the application is told it has started (chapter 13); an
        // extension's bootstrap has already validated it, before AfterDeploymentValidation
        beanContext.getBean(CdiBeanContainer.class).validateDeployment();
        // the order of section 2.9: the application context is initialized first, and Startup follows. Recorded
        // before its observers are told, so that one failing after another was told still has the application
        // context's destruction announced as the failed start is rolled back (section 6.7.2)
        beanContext.setAttribute(APPLICATION_CONTEXT_INITIALIZED, Boolean.TRUE);
        fire(new Object(), Object.class, Set.of(Initialized.Literal.of(ApplicationScoped.class)));
        initializeEagerBeans();
        fire(new Startup(), Startup.class, Set.of());
        started = true;
    }

    /**
     * Creates the contextual instance of every eagerly initialized bean (CDI 5.0, section 2.9): no later than
     * {@code Startup} is fired, as if by an observer of it of the lowest priority there is. An eager bean is in the
     * application scope, so what the bean context hands out for it is its client proxy, and the instance behind it
     * is what is created. The beans are found by the record the processor writes on them, which is on no other.
     */
    private void initializeEagerBeans() {
        for (BeanDefinition<Object> definition
            : beanContext.getBeanDefinitions(Qualifiers.byStereotype(CdiBeanAttributes.class))) {
            if (CdiResolution.isEager(definition) && CdiBeanQualifier.isBean(definition)) {
                Object reference = beanContext.getBean(definition);
                if (reference instanceof InterceptedProxy<?> proxy) {
                    proxy.interceptedTarget();
                }
            }
        }
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
    public void addShutdownCallback(Runnable release) {
        releases.add(release);
    }

    @Override
    public int getOrder() {
        // after every other listener of the shutdown, which may still reach for an application scoped bean
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        if (event.getSource() != beanContext
            || beanContext.removeAttribute(APPLICATION_CONTEXT_INITIALIZED, Boolean.class).isEmpty()) {
            // a container whose start failed before the application was told its application context was
            // initialized tells it nothing of the way down: what its start created is destroyed by Micronaut, the
            // scopes with it
            return;
        }
        // Shutdown first, then the application context says it is going. One observer throwing must not
        // silence the events after it - cleanup keyed on Destroyed still runs - nor stop the context from
        // stopping, so what an observer threw is logged, as what a bean throws as it is destroyed is. Shutdown
        // pairs with Startup: a start that failed before Startup was fired to its end, which is rolled back, is no
        // shutdown of an application that started, and the application context, which was announced, is still
        // destroyed between its two events, as Quarkus' ArC does
        if (started) {
            during("Shutdown", () -> fire(new Shutdown(), Shutdown.class, Set.of()));
        }
        // the requests still open are the first context to go: what their beans use as they are destroyed - an
        // application scoped bean, a singleton - is still there, and the observers of their end are told before
        // the application context says it is going
        during("the destruction of the requests still open", requestScope::destroyLive);
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
        } catch (RuntimeException | Error e) {
            // an Error too: Micronaut destroys the singletons only once this listener returns
            LOG.error("The container failed during {} as it stopped", what, e);
        }
    }

    private void fire(Object event, Class<?> type, Set<Annotation> qualifiers) {
        observers.notifyObservers(event, Argument.of(type), qualifiers, false);
    }
}
