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
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.context.scope.CustomScope;
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
    private final @Nullable CustomScope<?> holder;

    private CdiContext(Class<? extends Annotation> scope,
                       BooleanSupplier active,
                       @Nullable CustomScope<?> holder) {
        this.scope = scope;
        this.active = active;
        this.holder = holder;
    }

    /**
     * The context of the application scope, whose instances live as long as the container.
     *
     * @param scope            The scope annotation
     * @param applicationScope The scope that holds them
     * @return The context
     */
    public static CdiContext ofApplication(Class<? extends Annotation> scope, ApplicationScope applicationScope) {
        return new CdiContext(scope, () -> true, applicationScope);
    }

    /**
     * The context of the request scope, which is active only while a request is being handled.
     *
     * @param scope        The scope annotation
     * @param requestScope The scope that knows whether one is
     * @return The context
     */
    public static CdiContext ofRequest(Class<? extends Annotation> scope, RequestScope requestScope) {
        return new CdiContext(scope, requestScope::isActive, requestScope);
    }

    /**
     * The context of a scope that holds nothing of its own: the dependent pseudo-scope, whose instances belong
     * to whatever asked for them, and the singleton scope, which Micronaut holds itself.
     *
     * @param scope The scope annotation
     * @return The context
     */
    public static CdiContext holdingNothing(Class<? extends Annotation> scope) {
        return new CdiContext(scope, () -> true, null);
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
            // the dependent pseudo-scope holds nothing: what is created belongs to whoever asked
            return contextual.create(creationalContext);
        }
        Held<T> held = existing(store, contextual);
        if (held != null) {
            return held.instance();
        }
        T instance = contextual.create(creationalContext);
        if (instance != null) {
            store.put(contextual, new Held<>(contextual, instance, creationalContext));
        }
        return instance;
    }

    @Override
    public <T> @Nullable T get(Contextual<T> contextual) {
        requireActive();
        Map<Contextual<?>, Held<?>> store = store(false);
        if (store == null) {
            return null;
        }
        Held<T> held = existing(store, contextual);
        return held == null ? null : held.instance();
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
            Held<?> held = store.remove(contextual);
            if (held != null) {
                held.destroy();
                return;
            }
        }
        // not something a program handed in, so it is a bean of the container: the instance Micronaut holds in
        // the scope is destroyed and forgotten, and the next reference through the proxy is a fresh one
        if (contextual instanceof CdiBean<?> bean) {
            // matched by the bean's definition: getBeanClass() of a produced bean is the producer's declaring
            // class, which is not what the scope holds
            if (holder instanceof ApplicationScope applicationScope) {
                applicationScope.destroyInstanceOf(bean.definition());
            } else if (holder instanceof RequestScope requestScope) {
                requestScope.destroyInstanceOf(bean.definition());
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
            return ((CustomScope<Annotation>) holder).getOrCreate(new StoreCreation());
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
     * @param <T>               The type of the instance
     */
    private record Held<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {

        void destroy() {
            contextual.destroy(instance, creationalContext);
        }
    }

    /**
     * Creates the store inside the scope, as the one bean of the scope this module itself holds there: the
     * scope destroys every bean it holds when it ends, and destroying the store is what releases everything a
     * program handed in.
     */
    private static final class StoreCreation implements BeanCreationContext<Map<Contextual<?>, Held<?>>> {

        private static final BeanIdentifier ID = CONTEXTUAL_STORE_ID;

        @Override
        public BeanDefinition<Map<Contextual<?>, Held<?>>> definition() {
            throw new UnsupportedOperationException("The store of a context is not a bean with a definition");
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
                    throw new UnsupportedOperationException("The store of a context is not a bean with a "
                        + "definition");
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
                    for (Held<?> held : List.copyOf(store.values())) {
                        held.destroy();
                    }
                    store.clear();
                }
            };
        }
    }
}
