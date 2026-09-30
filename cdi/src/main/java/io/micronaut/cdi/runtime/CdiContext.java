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
import io.micronaut.cdi.context.RequestScope;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final io.micronaut.context.@Nullable BeanContext singletons;

    private CdiContext(Class<? extends Annotation> scope,
                       BooleanSupplier active,
                       @Nullable AbstractConcurrentCustomScope<?> holder,
                       io.micronaut.context.@Nullable BeanContext singletons) {
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
                                         io.micronaut.context.BeanContext beanContext) {
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
        Map<Contextual<?>, Held<?>> store = store(true);
        if (store == null) {
            // the dependent pseudo-scope holds nothing: what is created belongs to whoever asked. A singleton
            // is held by Micronaut
            return instanceOf(contextual, creationalContext);
        }
        synchronized (store) {
            Held<T> held = existing(store, contextual);
            if (held != null) {
                return held.instance();
            }
        }
        // created outside the lock — creation may be arbitrarily slow, or reach back into this context
        T instance = instanceOf(contextual, creationalContext);
        if (instance == null) {
            return null;
        }
        Held<T> created = new Held<>(contextual, instance, creationalContext, !isHeldByMicronaut(contextual));
        Held<T> raced;
        synchronized (store) {
            raced = existing(store, contextual);
            if (raced == null) {
                store.put(contextual, created);
                return instance;
            }
        }
        // another thread stored first: one instance per contextual per context, so ours is let go
        created.destroy();
        return raced.instance();
    }

    /**
     * The instance this context holds of a contextual it holds none of yet. A contextual a program handed in is
     * asked to create it. A bean of the container is held by the scope Micronaut keeps its instances in, which
     * is this context: the instance is the one that scope creates, once, since creating one through the bean
     * would be a second instance of the scope.
     */
    private <T> T instanceOf(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        if (isHeldByMicronaut(contextual)) {
            return ((CdiBean<T>) contextual).scopedInstance();
        }
        return contextual.create(creationalContext);
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
        Map<Contextual<?>, Held<?>> store = store(false);
        if (store != null) {
            synchronized (store) {
                Held<T> held = existing(store, contextual);
                if (held != null) {
                    return held.instance();
                }
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
                .map(io.micronaut.context.BeanRegistration::bean)
                .orElse(null);
        }
        if (singletons != null) {
            for (io.micronaut.context.BeanRegistration<?> registration
                : singletons.getActiveBeanRegistrations(bean.definition().getBeanType())) {
                if (bean.equals(new CdiBean<>(singletons, registration.getBeanDefinition()))) {
                    return (T) registration.bean();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable Held<T> existing(Map<Contextual<?>, Held<?>> store, Contextual<T> contextual) {
        return (Held<T>) store.get(contextual);
    }

    @Override
    public boolean isActive() {
        return active.getAsBoolean();
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        requireActive();
        Map<Contextual<?>, Held<?>> store = store(false);
        if (store != null) {
            Held<?> held;
            synchronized (store) {
                held = store.remove(contextual);
            }
            if (held != null) {
                held.destroy();
                if (held.owned()) {
                    return;
                }
            }
        }
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
        io.micronaut.context.BeanContext beanContext = java.util.Objects.requireNonNull(singletons);
        for (io.micronaut.context.BeanRegistration<?> registration
            : beanContext.getActiveBeanRegistrations(bean.definition().getBeanType())) {
            if (bean.equals(new CdiBean<>(beanContext, registration.getBeanDefinition()))) {
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
    private @Nullable Map<Contextual<?>, Held<?>> store(boolean forCreation) {
        if (holder == null) {
            return null;
        }
        try {
            return ((AbstractConcurrentCustomScope<Annotation>) holder).getOrCreate(new StoreCreation());
        } catch (ContextNotActiveException e) {
            if (forCreation) {
                throw e;
            }
            return null;
        }
    }

    private void requireActive() {
        if (!isActive()) {
            throw new ContextNotActiveException("The " + scope.getName() + " context is not active");
        }
    }

    /**
     * One instance a program's contextual created, with the creational context it was created in, so that
     * destroying it hands both back the way section 2.5.1 says.
     *
     * @param contextual        The contextual that created it
     * @param instance          The instance
     * @param creationalContext The creational context it was created in
     * @param owned             Whether this context is what destroys the instance: it is for an instance a
     *                          program's contextual created, and is not for the instance of a bean of the
     *                          container, which the scope Micronaut holds it in destroys. Only the creational
     *                          context of such an instance is this context's to release
     * @param <T>               The type of the instance
     */
    private record Held<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext,
                           boolean owned) {

        void destroy() {
            if (owned) {
                contextual.destroy(instance, creationalContext);
            } else {
                creationalContext.release();
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
    private static final class StoreCreation implements BeanCreationContext<Map<Contextual<?>, Held<?>>> {

        private static final BeanIdentifier ID = CONTEXTUAL_STORE_ID;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static final BeanDefinition<Map<Contextual<?>, Held<?>>> DEFINITION = (BeanDefinition)
            RuntimeBeanDefinition.of(StoreCreation.class, StoreCreation::new);

        @Override
        public BeanDefinition<Map<Contextual<?>, Held<?>>> definition() {
            return DEFINITION;
        }

        @Override
        public BeanIdentifier id() {
            return ID;
        }

        @Override
        public CreatedBean<Map<Contextual<?>, Held<?>>> create() {
            Map<Contextual<?>, Held<?>> store = new LinkedHashMap<>();
            return new CreatedBean<>() {
                @Override
                public BeanDefinition<Map<Contextual<?>, Held<?>>> definition() {
                    return DEFINITION;
                }

                @Override
                public Map<Contextual<?>, Held<?>> bean() {
                    return store;
                }

                @Override
                public BeanIdentifier id() {
                    return ID;
                }

                @Override
                public void close() {
                    List<Held<?>> drained;
                    synchronized (store) {
                        drained = List.copyOf(store.values());
                        store.clear();
                    }
                    RuntimeException failure = null;
                    for (Held<?> held : drained) {
                        try {
                            held.destroy();
                        } catch (RuntimeException e) {
                            if (failure == null) {
                                failure = e;
                            } else {
                                failure.addSuppressed(e);
                            }
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
        }
    }
}
