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

import io.micronaut.cdi.internal.context.ApplicationScope;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.exceptions.BeanDestructionException;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The context of one scope, as the specification describes it: the thing that holds the instance of a bean in
 * that scope, creates one when asked with a creational context, and can destroy one it holds.
 *
 * <p>The instances of the container's own beans are held by Micronaut, in the
 * {@link io.micronaut.context.scope.CustomScope} of the scope. What the specification adds is that a program can
 * hand the context a {@link Contextual} of its own and have the context hold what it creates; those are held
 * here too, in a store that lives inside the same custom scope — created through it, so that it is destroyed
 * when the scope is, and every creational context handed in is released exactly when the scope ends.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiContext implements AlterableContext {

    /**
     * The identifier the store of a context is held in a scope under, so that the scope can tell it apart from
     * the beans it holds.
     */
    public static final BeanIdentifier CONTEXTUAL_STORE_ID = BeanIdentifier.of("io.micronaut.cdi.held-contextuals");

    private final Class<? extends Annotation> scope;
    private final BooleanSupplier active;
    private final @Nullable AbstractConcurrentCustomScope<?> holder;
    private final @Nullable BeanContext singletons;

    private CdiContext(Class<? extends Annotation> scope,
                       BooleanSupplier active,
                       @Nullable AbstractConcurrentCustomScope<?> holder,
                       @Nullable BeanContext singletons) {
        this.scope = scope;
        this.active = active;
        this.holder = holder;
        this.singletons = singletons;
    }

    /**
     * The context of the application scope, whose instances live as long as the container.
     *
     * @param scope            The scope annotation
     * @param applicationScope The scope that holds them
     * @return The context
     */
    public static CdiContext ofApplication(Class<? extends Annotation> scope, ApplicationScope applicationScope) {
        return new CdiContext(scope, () -> true, applicationScope, null);
    }

    /**
     * The context of the request scope, which is active only while a request is being handled.
     *
     * @param scope        The scope annotation
     * @param requestScope The scope that knows whether one is
     * @return The context
     */
    public static CdiContext ofRequest(Class<? extends Annotation> scope, RequestScope requestScope) {
        return new CdiContext(scope, requestScope::isActive, requestScope, null);
    }

    /**
     * The context of a scope that holds nothing of its own: the dependent pseudo-scope, whose instances belong
     * to whatever asked for them.
     *
     * @param scope The scope annotation
     * @return The context
     */
    public static CdiContext holdingNothing(Class<? extends Annotation> scope) {
        return new CdiContext(scope, () -> true, null, null);
    }

    /**
     * The context of the singleton scope, whose instances Micronaut holds itself: a program's contextual is
     * held nowhere, and a bean's instance is the singleton Micronaut created for it.
     *
     * @param scope       The scope annotation
     * @param beanContext The context that holds the singletons
     * @return The context
     */
    public static CdiContext ofSingleton(Class<? extends Annotation> scope,
                                         BeanContext beanContext) {
        return new CdiContext(scope, () -> true, null, beanContext);
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return scope;
    }

    @Override
    public <T> @Nullable T get(Contextual<T> contextual, @Nullable CreationalContext<T> creationalContext) {
        requireActive();
        if (creationalContext == null) {
            // the contract of a null creational context is a plain lookup: what the context holds, or nothing
            return get(contextual);
        }
        if (isHeldByMicronaut(contextual)) {
            // a bean of the container is held by the scope Micronaut keeps the instances of its beans in, which is
            // this context: the instance is the one that scope holds, asked of it every time. Only the creational
            // context handed in is kept here, to be released as the context ends (section 6.2): the instance is
            // never answered from here, which would go on handing it out after Micronaut destroyed it
            T instance = ((CdiBean<T>) contextual).scopedInstance();
            Store store = store(true);
            if (store != null) {
                Held<T> release = new Held<>(contextual, null, creationalContext, false);
                if (store.publish(release) == null) {
                    // the context ended: nothing will release this one later. Where one is kept already, the
                    // instance is the one it was kept for, and the creational context kept with it is released
                    // as the context ends, not now, while the instance is in use
                    release.destroy();
                }
            }
            return instance;
        }
        Store store = store(true);
        if (store == null) {
            // the dependent pseudo-scope holds nothing: what is created belongs to whoever asked
            return contextual.create(creationalContext);
        }
        Held<T> held = store.find(contextual);
        if (held != null && held.owned()) {
            return held.instance();
        }
        // created outside the lock — creation may be arbitrarily slow, or reach back into this context
        T instance = contextual.create(creationalContext);
        if (instance == null) {
            return null;
        }
        Held<T> created = new Held<>(contextual, instance, creationalContext, true);
        Held<T> kept = store.publish(created);
        if (kept == created) {
            return instance;
        }
        // another thread stored first - one instance per contextual per context - or the context ended while the
        // instance was created, and a context that has ended keeps nothing: ours is let go either way
        created.destroy();
        if (kept == null) {
            throw new ContextNotActiveException("The " + scope.getName() + " context ended while an instance of "
                + contextual + " was created for it");
        }
        return kept.instance();
    }

    /**
     * Whether the instance of a contextual is held, and destroyed, by the scope Micronaut keeps the instances of
     * its beans in: a bean of the container is, and a contextual a program handed in is held by this context.
     */
    private boolean isHeldByMicronaut(Contextual<?> contextual) {
        return contextual instanceof CdiBean<?> bean && (holder != null || singletons != null)
            && bean.getClass() == CdiBean.class;
    }

    @Override
    public <T> @Nullable T get(Contextual<T> contextual) {
        requireActive();
        Store store = store(false);
        if (store != null) {
            Held<T> held = store.find(contextual);
            if (held != null && held.owned()) {
                return held.instance();
            }
        }
        // not something a program handed in, so it may be a bean of the container: the instance the scope
        // created for it — through its client proxy, most often — is the one the scope holds for its definition
        if (contextual instanceof CdiBean<T> bean) {
            return heldInstanceOf(bean);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T heldInstanceOf(CdiBean<T> bean) {
        if (holder != null) {
            return holder.findBeanRegistration(bean.definition())
                .map(BeanRegistration::bean)
                .orElse(null);
        }
        if (singletons != null) {
            // the singletons Micronaut holds of the class, told apart by the container's own bean of each definition
            // rather than a wrapper made for each: Micronaut keeps them by definition, but answers that by class
            CdiBeanContainer container = singletons.getBean(CdiBeanContainer.class);
            for (BeanRegistration<?> registration
                : singletons.getActiveBeanRegistrations(bean.definition().getBeanType())) {
                if (bean.equals(container.canonicalBean(registration.getBeanDefinition()))) {
                    return (T) registration.bean();
                }
            }
        }
        return null;
    }

    /**
     * The one context among the contexts of a scope that is active on the current thread.
     *
     * @param contexts The contexts of the scope
     * @param scope    The scope, for the message where more than one is active
     * @param <C>      The kind of context
     * @return The active context, or {@code null} where none is
     * @throws IllegalStateException Where more than one is active
     */
    public static <C extends Context> @Nullable C activeAmong(Iterable<C> contexts, Class<?> scope) {
        C active = null;
        for (C context : contexts) {
            if (context.isActive()) {
                if (active != null) {
                    throw new IllegalStateException("More than one context of " + scope.getName()
                        + " is active on the current thread");
                }
                active = context;
            }
        }
        return active;
    }

    @Override
    public boolean isActive() {
        return active.getAsBoolean();
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        requireActive();
        Store store = store(false);
        if (store != null) {
            Held<?> held = store.remove(contextual);
            if (held != null && held.owned()) {
                held.destroy();
                return;
            }
            if (held != null) {
                // the instance Micronaut holds is destroyed whatever releasing the creational context threw, and
                // the first failure is the one reported
                Throwable failure = null;
                try {
                    held.destroy();
                } catch (RuntimeException | Error e) {
                    failure = e;
                }
                try {
                    destroyHeldByMicronaut(contextual);
                } catch (RuntimeException | Error e) {
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure != null) {
                    throw (RuntimeException) failure;
                }
                return;
            }
        }
        destroyHeldByMicronaut(contextual);
    }

    private void destroyHeldByMicronaut(Contextual<?> contextual) {
        // not something a program handed in, so it is a bean of the container: the instance Micronaut holds in
        // the scope is destroyed and forgotten, and the next reference through the proxy is a fresh one
        if (contextual instanceof CdiBean<?> bean) {
            if (holder != null) {
                // matched by the bean's definition, which may be the one of its client proxy: getBeanClass() of
                // a produced bean is the producer's declaring class, which is not what the scope holds
                holder.remove(bean.definition());
            } else if (singletons != null) {
                destroySingleton(bean);
            }
        }
    }

    /**
     * Destroys the singleton Micronaut holds for a bean, through Micronaut: its pre-destroy callback or disposer
     * runs, its dependent objects go with it, and it is no longer registered, so the next one asked for is created
     * anew. A singleton that was never created has nothing to destroy.
     */
    private void destroySingleton(CdiBean<?> bean) {
        BeanContext beanContext = Objects.requireNonNull(singletons);
        CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
        for (BeanRegistration<?> registration
            : beanContext.getActiveBeanRegistrations(bean.definition().getBeanType())) {
            if (bean.equals(container.canonicalBean(registration.getBeanDefinition()))) {
                beanContext.destroyBean(registration);
                return;
            }
        }
    }

    /**
     * The store of the contextuals a program handed this context, held inside the scope itself so that it is
     * destroyed — and every creational context in it released — exactly when the scope is.
     */
    @SuppressWarnings("unchecked")
    private @Nullable Store store(boolean forCreation) {
        if (holder == null) {
            return null;
        }
        if (!forCreation) {
            // asking what the context holds, or destroying what it may hold, puts nothing into it: the store is the
            // one the scope holds already, if any
            return holder.findBeanRegistration(StoreCreation.DEFINITION)
                .map(BeanRegistration::bean)
                .orElse(null);
        }
        return ((AbstractConcurrentCustomScope<Annotation>) holder).getOrCreate(new StoreCreation());
    }

    private void requireActive() {
        if (!isActive()) {
            throw new ContextNotActiveException("The " + scope.getName() + " context is not active");
        }
    }

    /**
     * One instance a program's contextual created, with the creational context it was created in, so that
     * destroying it hands both back the way section 2.5.1 says; or the creational context a bean of the container
     * was asked for with, which is released as the context ends.
     *
     * @param contextual        The contextual that created it
     * @param instance          The instance, which is not kept for a bean of the container: Micronaut's scope holds
     *                          that one
     * @param creationalContext The creational context it was created in
     * @param owned             Whether the instance is this context's to hand out and destroy: it is for what a
     *                          program's contextual created, and is not for the instance of a bean of the container,
     *                          which the scope Micronaut holds it in answers and destroys
     * @param <T>               The type of the instance
     */
    private record Held<T>(Contextual<T> contextual, @Nullable T instance, CreationalContext<T> creationalContext,
                           boolean owned) {

        void destroy() {
            if (owned) {
                contextual.destroy(Objects.requireNonNull(instance), creationalContext);
            } else {
                creationalContext.release();
            }
        }
    }

    /**
     * The instances of the contextuals a program handed one context, from the first creation in the context until
     * the context ends. Ending it is final: an instance created for the context as it ends is not kept, since
     * nothing would destroy it again, and while the instances are destroyed the ones not yet destroyed are still
     * found, so that a destruction callback asking the context for a peer finds the peer rather than creating
     * another into a context on its way out.
     */
    private static final class Store {

        private final Map<Contextual<?>, Held<?>> held = new LinkedHashMap<>();
        private boolean ended;

        @SuppressWarnings("unchecked")
        synchronized <T> @Nullable Held<T> find(Contextual<T> contextual) {
            return (Held<T>) held.get(contextual);
        }

        /**
         * Keeps what was created, unless another instance of its contextual is kept already or the context ended.
         *
         * @return What is kept: the given instance, the one kept before it, or {@code null} once the context ended
         */
        @SuppressWarnings("unchecked")
        synchronized <T> @Nullable Held<T> publish(Held<T> created) {
            if (ended) {
                return null;
            }
            Held<T> raced = (Held<T>) held.get(created.contextual());
            if (raced != null) {
                return raced;
            }
            held.put(created.contextual(), created);
            return created;
        }

        synchronized @Nullable Held<?> remove(Contextual<?> contextual) {
            return held.remove(contextual);
        }

        private synchronized boolean remove(Held<?> each) {
            return held.remove(each.contextual(), each);
        }

        /**
         * Ends the context: what it holds is destroyed, each instance taken out as it is destroyed, and nothing is
         * kept afterwards.
         */
        void end() {
            List<Held<?>> drained;
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                drained = List.copyOf(held.values());
            }
            // every instance is destroyed whatever another's destruction threw - an Error among it, or the very
            // exception another threw - and the first failure is reported once they all are
            Throwable failure = null;
            for (Held<?> each : drained) {
                // one a destruction callback destroyed meanwhile is gone already
                if (!remove(each)) {
                    continue;
                }
                try {
                    each.destroy();
                } catch (RuntimeException | Error e) {
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
        }
    }

    /**
     * Creates the store inside the scope, as the one bean of the scope this module itself holds there: the
     * scope destroys every bean it holds when it ends, and destroying the store is what releases everything a
     * program handed in.
     *
     * <p>The store is not a bean of the application, but the scope asks what it holds for its definition, and
     * does so for every entry whenever it looks a bean up by its definition. It answers with a definition of its
     * own, which no bean of the container equals, and which is never used to create anything.</p>
     */
    private static final class StoreCreation implements BeanCreationContext<Store> {

        private static final BeanIdentifier ID = CONTEXTUAL_STORE_ID;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static final BeanDefinition<Store> DEFINITION = (BeanDefinition)
            RuntimeBeanDefinition.of(StoreCreation.class, StoreCreation::new);

        @Override
        public BeanDefinition<Store> definition() {
            return DEFINITION;
        }

        @Override
        public BeanIdentifier id() {
            return ID;
        }

        @Override
        public CreatedBean<Store> create() {
            Store store = new Store();
            return new CreatedBean<>() {
                @Override
                public BeanDefinition<Store> definition() {
                    return DEFINITION;
                }

                @Override
                public Store bean() {
                    return store;
                }

                @Override
                public BeanIdentifier id() {
                    return ID;
                }

                @Override
                public void close() {
                    try {
                        store.end();
                    } catch (RuntimeException | Error e) {
                        // reported the way the scope expects a bean's destruction to fail, so that the scope goes on
                        // destroying its other beans rather than stopping at the store
                        throw new BeanDestructionException(DEFINITION, e);
                    }
                }
            };
        }
    }
}
