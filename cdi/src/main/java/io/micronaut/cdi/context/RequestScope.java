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
package io.micronaut.cdi.context;

import io.micronaut.cdi.annotation.CdiRequestScope;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The context of the request scope, which holds one instance of every request scoped bean for as long as the
 * request that asked for it is being handled.
 *
 * <p>A request is not anything the container knows about, so whoever does has to say so: the scope is begun
 * around the work that is one request, and the beans of that request live in the {@link PropagatedContext} while
 * it runs. {@link io.micronaut.cdi.runtime.MicronautRequestContextController} is that for an application that
 * says it itself, and the {@code jakarta.enterprise.context.control.ActivateRequestContext} advice is that for a
 * method that is one request.</p>
 *
 * <p>Holding the instances in the propagated context rather than in a thread local of this class is what lets a
 * request outlive the thread it began on: work handed to an executor, a reactive operator or a virtual thread
 * carries the context with it, and reaches the same request scoped instances there. A thread local would have
 * given that work a different request, or none.</p>
 *
 * <p>Asking for a request scoped bean while no request is being handled is the error the specification calls a
 * {@link ContextNotActiveException}, and is reported as one. The client proxy of a request scoped bean is what
 * makes that possible: the proxy can be injected anywhere, and only reaching through it needs a request.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class RequestScope extends AbstractConcurrentCustomScope<CdiRequestScope> {

    /**
     * The activations begun by {@link #activate()} on this thread, innermost first, each holding what it has to
     * undo. Only the enter-and-exit form needs this: it has nowhere else to keep the handle that ends the
     * propagation, since the caller returns between the two halves. The instances themselves are in the
     * propagated context either way.
     */
    private final ThreadLocal<Deque<Activation>> activations = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * The requests suspended on this thread, innermost first: a request the kit set inactive keeps everything
     * it holds and waits here until it is set active again.
     */
    private final ThreadLocal<Deque<Instances>> suspended = ThreadLocal.withInitial(ArrayDeque::new);

    private final io.micronaut.context.BeanContext beanContext;

    public RequestScope(io.micronaut.context.BeanContext beanContext) {
        super(CdiRequestScope.class);
        this.beanContext = beanContext;
    }

    /**
     * Runs one creation with the request context active, quietly: section 2.5.6 has the request context active
     * during every bean's {@code @PostConstruct} callback, and a creation that finds no request under way is
     * given one that spans the creation alone. No lifecycle event is fired for it — it is not a request the
     * application began, only the footing the specification promises a callback.
     *
     * @param creation The creation
     * @param <V>      What the creation makes
     * @return What the creation made
     */
    public <V> V duringCreation(Supplier<V> creation) {
        if (isActive()) {
            return creation.get();
        }
        Instances instances = newInstances();
        try {
            return PropagatedContext.getOrEmpty().plus(instances).propagate(creation::get);
        } finally {
            destroyScope(instances.beans());
        }
    }

    /**
     * Tells the observers of section 2.8.6 that a request context was initialized, is about to be destroyed,
     * or was destroyed: the qualifier carries the moment, and the payload in SE is a plain object, there being
     * no request object to hand over.
     */
    private void requestContextEvent(java.lang.annotation.Annotation moment) {
        beanContext.findBean(io.micronaut.cdi.runtime.ObserverRegistry.class).ifPresent(observers ->
            observers.notifyObservers(new Object(), io.micronaut.core.type.Argument.OBJECT_ARGUMENT,
                java.util.Set.of(moment), false));
    }

    private void initializedEvent() {
        requestContextEvent(jakarta.enterprise.context.Initialized.Literal.of(
            jakarta.enterprise.context.RequestScoped.class));
    }

    private void beforeDestroyedEvent() {
        requestContextEvent(jakarta.enterprise.context.BeforeDestroyed.Literal.of(
            jakarta.enterprise.context.RequestScoped.class));
    }

    private void destroyedEvent() {
        requestContextEvent(jakarta.enterprise.context.Destroyed.Literal.of(
            jakarta.enterprise.context.RequestScoped.class));
    }

    @Override
    protected @Nullable Map<BeanIdentifier, CreatedBean<?>> getScopeMap(boolean forCreation) {
        Instances instances = currentInstances();
        if (instances == null) {
            if (forCreation) {
                throw new ContextNotActiveException("The request scope is not active. Begin a request with "
                    + "jakarta.enterprise.context.control.RequestContextController, or annotate the method that "
                    + "is the request with jakarta.enterprise.context.control.ActivateRequestContext");
            }
            return null;
        }
        return instances.beans();
    }

    /**
     * Runs the given work as one request, destroying the beans of that request when it returns.
     *
     * <p>If a request is being handled already the work is part of that one, which is what the specification
     * asks of an activation that finds the context active.</p>
     *
     * @param work The work that is one request
     */
    public void run(Runnable work) {
        if (isActive()) {
            work.run();
            return;
        }
        Instances instances = newInstances();
        try {
            PropagatedContext.getOrEmpty().plus(instances).propagate(() -> {
                initializedEvent();
                try {
                    work.run();
                } finally {
                    // fired while the request is still being handled, which is what "before" means
                    beforeDestroyedEvent();
                }
            });
        } finally {
            destroyScope(instances.beans());
            destroyedEvent();
        }
    }

    /**
     * Runs the given work as one request and returns what it returned, destroying the beans of that request when
     * it returns.
     *
     * @param work The work that is one request
     * @param <V>  What the work returns
     * @return What the work returned
     */
    public <V> V supply(Supplier<V> work) {
        if (isActive()) {
            return work.get();
        }
        Instances instances = newInstances();
        try {
            return PropagatedContext.getOrEmpty().plus(instances).propagate(() -> {
                initializedEvent();
                try {
                    return work.get();
                } finally {
                    beforeDestroyedEvent();
                }
            });
        } finally {
            destroyScope(instances.beans());
            destroyedEvent();
        }
    }

    /**
     * Runs the given work as one request and returns what it returned, destroying the beans of that request when
     * it returns.
     *
     * @param work The work that is one request
     * @param <V>  What the work returns
     * @return What the work returned
     * @throws Exception What the work threw
     */
    public <V> V call(Callable<V> work) throws Exception {
        if (isActive()) {
            return work.call();
        }
        Instances instances = newInstances();
        try {
            return PropagatedContext.getOrEmpty().plus(instances).propagateCall(() -> {
                initializedEvent();
                try {
                    return work.call();
                } finally {
                    beforeDestroyedEvent();
                }
            });
        } finally {
            destroyScope(instances.beans());
            destroyedEvent();
        }
    }

    /**
     * Whether the scope already holds an instance of the given type, which is what a conditional observer of
     * section 2.8.2 asks.
     *
     * @param beanType The type of the bean
     * @return Whether an instance is held
     */
    public boolean holdsInstanceOf(Class<?> beanType) {
        Map<BeanIdentifier, CreatedBean<?>> beans = getScopeMap(false);
        if (beans == null) {
            return false;
        }
        for (CreatedBean<?> created : java.util.List.copyOf(beans.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                continue;
            }
            if (beanType.isAssignableFrom(created.definition().getBeanType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Destroys the instance of the given bean type the request holds, if it holds one.
     *
     * @param beanType The type of the bean
     * @return Whether an instance was destroyed
     */
    /**
     * Destroys the instance of the given bean this scope holds, if it holds one: matched by the definition
     * rather than by a class, because a produced bean's class — the producer's declaring class — says nothing
     * about the produced instance, and one class may be the type of several beans.
     *
     * @param definition The bean's definition, possibly the proxy's
     * @return Whether an instance was destroyed
     */
    public boolean destroyInstanceOf(io.micronaut.inject.BeanDefinition<?> definition) {
        Map<BeanIdentifier, CreatedBean<?>> beans = getScopeMap(false);
        if (beans == null) {
            return false;
        }
        String targetType = definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy
            ? proxy.getTargetDefinitionType().getName() : null;
        for (CreatedBean<?> created : java.util.List.copyOf(beans.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                continue;
            }
            io.micronaut.inject.BeanDefinition<?> held = created.definition();
            if (held.equals(definition)
                || targetType != null && held.getClass().getName().equals(targetType)) {
                beans.remove(created.id());
                created.close();
                return true;
            }
        }
        return false;
    }

    public boolean destroyInstanceOf(Class<?> beanType) {
        Map<BeanIdentifier, CreatedBean<?>> beans = getScopeMap(false);
        if (beans == null) {
            return false;
        }
        for (CreatedBean<?> created : java.util.List.copyOf(beans.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                // the store a context keeps its handed-in contextuals in is not a bean of the scope
                continue;
            }
            if (beanType.isAssignableFrom(created.definition().getBeanType())) {
                // taken out of the map before it is closed: the scope's own remove closes the bean but leaves
                // the entry behind, and a destroyed instance must not be served again
                beans.remove(created.id());
                created.close();
                return true;
            }
        }
        return false;
    }

    /**
     * Makes the request context inactive on the calling thread without destroying what it holds, so that it
     * can be resumed: what the kit's harness calls setting a context inactive.
     */
    @SuppressWarnings("deprecation")
    public void suspend() {
        Deque<Activation> stack = activations.get();
        Activation activation = stack.poll();
        if (stack.isEmpty()) {
            activations.remove();
        }
        if (activation == null) {
            return;
        }
        activation.scope().close();
        suspended.get().push(activation.instances());
    }

    /**
     * Resumes the request context last suspended on the calling thread, with everything it held.
     */
    @SuppressWarnings("deprecation")
    public void resume() {
        Deque<Instances> stack = suspended.get();
        Instances instances = stack.poll();
        if (stack.isEmpty()) {
            suspended.remove();
        }
        if (instances == null) {
            activate();
            return;
        }
        PropagatedContext.Scope scope = PropagatedContext.getOrEmpty().plus(instances).propagate();
        activations.get().push(new Activation(instances, scope));
    }

    /**
     * Begins a request on the calling thread, if one is not under way already.
     *
     * <p>This is the enter-and-exit form the specification's
     * {@code jakarta.enterprise.context.control.RequestContextController} is written as, and the one form that
     * cannot be expressed as work to run: the caller returns between beginning the request and ending it. It
     * therefore needs {@link PropagatedContext#propagate()}, which holds the context in a thread local, and so
     * needs Micronaut's propagation to be in its thread-local mode. {@link #run}, {@link #supply} and
     * {@link #call} are the forms that work in either mode, and are what to reach for where the work is a
     * lambda.</p>
     *
     * @return Whether this call is what began it, and so is the one that has to end it
     */
    @SuppressWarnings("deprecation")
    public boolean activate() {
        if (isActive()) {
            return false;
        }
        Instances instances = newInstances();
        PropagatedContext.Scope scope = PropagatedContext.getOrEmpty().plus(instances).propagate();
        activations.get().push(new Activation(instances, scope));
        initializedEvent();
        return true;
    }

    /**
     * Ends the request begun on the calling thread by {@link #activate()}, destroying the beans that belong to it.
     *
     * <p>A request that is being handled but was not begun that way is left alone: a caller that did not begin a
     * request is not the one to end it.</p>
     */
    public void deactivate() {
        if (!isActive()) {
            throw new ContextNotActiveException("The request scope is not active on the current thread");
        }
        Deque<Activation> stack = activations.get();
        Activation activation = stack.poll();
        if (activation == null) {
            // active, but begun by run/supply/call rather than by activate, so not this caller's to end
            activations.remove();
            return;
        }
        if (stack.isEmpty()) {
            activations.remove();
        }
        try {
            // fired while the request is still being handled, which is what "before" means
            beforeDestroyedEvent();
        } finally {
            try {
                activation.scope().close();
            } finally {
                destroyScope(activation.instances().beans());
                destroyedEvent();
            }
        }
    }

    /**
     * Tells whether a request is being handled.
     *
     * @return Whether a request is being handled
     */
    public boolean isActive() {
        return currentInstances() != null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public void close() {
        activations.remove();
    }

    private @Nullable Instances currentInstances() {
        Instances instances = PropagatedContext.getOrEmpty().find(Instances.class).orElse(null);
        // a request of another container — another running alongside, or one already shut down whose request
        // was never ended — is not a request of this one
        return instances != null && instances.owner() == this ? instances : null;
    }

    private Instances newInstances() {
        return new Instances(this, new ConcurrentHashMap<>(8));
    }

    /**
     * The beans of one request, carried by the propagated context so that they reach wherever the request's work
     * goes. The map is mutable and the element holds it, because the context itself is immutable: beans are put
     * into the map as the request asks for them, rather than by replacing the element.
     *
     * @param owner The scope the request belongs to, each container having its own
     * @param beans The beans of the request
     */
    private record Instances(RequestScope owner, Map<BeanIdentifier, CreatedBean<?>> beans)
        implements PropagatedContextElement {
    }

    /**
     * One request begun by {@link #activate()}, and what ending it takes.
     *
     * @param instances The beans of the request
     * @param scope     The handle that ends the propagation
     */
    private record Activation(Instances instances, PropagatedContext.Scope scope) {
    }

}
