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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanDependencyGroup;
import io.micronaut.context.BeanDependencyResolver;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.Qualifier;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The dependent instances one owner creates - a lookup, a creational context, one notification of an observer -
 * which go together when the owner is done with them, the last created first.
 *
 * <p>Micronaut owns them. A lookup injected into a bean creates through the resolver Micronaut hands the bean,
 * so that what it creates is destroyed with the bean; anything else creates through a dependency group of its
 * own, opened as it creates its first instance and closed with it. A singleton or an instance of another scope
 * stays its scope's.</p>
 *
 * <p>As the context stops, Micronaut opens a new group for a listener of its shutdown event alone, and resolves
 * through a group or the resolver of a bean on the thread that stops it alone, so a lookup made from a
 * {@code @PreDestroy} that runs after it, or on another thread, gets neither. What such a lookup creates is held
 * here instead and destroyed the same way, as is an instance a context of an extension created, which Micronaut
 * did not.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class OwnedDependents implements AutoCloseable {

    private final BeanContext beanContext;
    private final @Nullable BeanDependencyResolver resolver;
    private @Nullable BeanDependencyGroup group;
    private boolean refused;
    /**
     * The dependent instances created through the resolver or the group, by which one is found again from the
     * instance it holds; Micronaut destroys them.
     */
    private final List<BeanRegistration<?>> created = new ArrayList<>(2);
    /**
     * What was created without Micronaut owning it, destroyed here.
     */
    private final List<CreatedBean<?>> held = new ArrayList<>(0);
    /**
     * The creation under way that the owner's lookups are made within, while it is: a bean looked up for the point
     * already current is resolved within it, and sees on its path what that creation is for.
     */
    private volatile RuntimeBeanDefinition.@Nullable LookupContext creation;

    private OwnedDependents(BeanContext beanContext, @Nullable BeanDependencyResolver resolver) {
        this.beanContext = beanContext;
        this.resolver = resolver;
    }

    /**
     * The dependents of an owner that has a dependency group of its own, opened as its first dependent is
     * created.
     *
     * @param beanContext The context the instances are created in
     * @return The dependents
     */
    public static OwnedDependents of(BeanContext beanContext) {
        return new OwnedDependents(beanContext, null);
    }

    /**
     * The dependents of an owner Micronaut hands a resolver of the bean it belongs to: what they create is
     * destroyed with that bean. Without one, the owner has a dependency group of its own.
     *
     * @param beanContext The context the instances are created in
     * @param resolver    The resolver of the bean, or {@code null}
     * @return The dependents
     */
    public static OwnedDependents ownedBy(BeanContext beanContext, @Nullable BeanDependencyResolver resolver) {
        return new OwnedDependents(beanContext, resolver);
    }

    /**
     * The registration of the bean of the given definition, asked for as the given type: a dependent instance
     * created for it belongs to the owner. The definition is the one a lookup chose, resolved as it is.
     *
     * @param definition The definition
     * @param type       The type it is asked for as
     * @param <T>        The bean type
     * @return The registration
     */
    public <T> BeanRegistration<T> registration(BeanDefinition<? extends T> definition, Argument<T> type) {
        if (resolver == null && !CdiResolution.isDependent(definition)) {
            // a bean of a scope is the scope's, and needs no group of the owner's opened for it
            return beanContext.getBeanRegistration(definition, type);
        }
        BeanDependencyResolver owner = owner();
        if (owner != null) {
            try {
                BeanRegistration<T> registration = owner.getBeanRegistration(definition, type);
                keep(registration, true);
                return registration;
            } catch (IllegalStateException e) {
                // the context is stopping, and Micronaut resolves for its owner on the thread that stops it alone
            }
        }
        BeanRegistration<T> registration = beanContext.getBeanRegistration(definition, type);
        keep(registration, false);
        return registration;
    }

    /**
     * The registration of the bean of the given definition, created for the point already current rather than for
     * an injection point of the lookup's own - the injection point metadata, which describes that point. Within a
     * creation under way (see {@link #within}) the bean is resolved within it, by
     * {@link RuntimeBeanDefinition.LookupContext#getBeanRegistration(BeanDefinition, Argument)}, so that the path
     * it sees is the creation's and says what the creation is for; it is a dependent of the bean being created, as
     * one the creation's own lookups create is. Otherwise it is {@link #registration(BeanDefinition, Argument)}.
     *
     * @param definition The definition
     * @param type       The type it is asked for as
     * @param <T>        The bean type
     * @return The registration
     */
    public <T> BeanRegistration<T> registrationAtCurrentPoint(BeanDefinition<? extends T> definition,
                                                              Argument<T> type) {
        RuntimeBeanDefinition.LookupContext within = creation;
        if (within == null) {
            return registration(definition, type);
        }
        BeanRegistration<T> registration = within.getBeanRegistration(definition, type);
        keep(registration, resolver != null);
        return registration;
    }

    /**
     * Makes the lookups of the owner within a creation while the given action runs: what is looked up for the point
     * already current is resolved within the creation (see {@link #registrationAtCurrentPoint}). A lookup the
     * created bean keeps resolves on its own once the creation is over.
     *
     * @param creationContext The creation under way
     * @param action          What runs within it
     * @param <R>             What the action returns
     * @return What the action returned
     */
    public <R> R within(RuntimeBeanDefinition.LookupContext creationContext, java.util.function.Supplier<R> action) {
        creation = creationContext;
        try {
            return action.get();
        } finally {
            creation = null;
        }
    }

    /**
     * The registration of the bean a type and a qualifier resolve to: a dependent instance created for it belongs
     * to the owner.
     *
     * @param type      The type
     * @param qualifier The qualifier, or {@code null}
     * @param <T>       The bean type
     * @return The registration
     */
    public <T> BeanRegistration<T> registration(Argument<T> type, @Nullable Qualifier<T> qualifier) {
        if (resolver == null) {
            BeanDefinition<T> definition = beanContext.findBeanDefinition(type, qualifier).orElse(null);
            if (definition != null && !CdiResolution.isDependent(definition)) {
                // a bean of a scope is the scope's, and needs no group of the owner's opened for it
                return beanContext.getBeanRegistration(definition, type);
            }
        }
        BeanDependencyResolver owner = owner();
        if (owner != null) {
            try {
                BeanRegistration<T> registration = owner.getBeanRegistration(type, qualifier);
                keep(registration, true);
                return registration;
            } catch (IllegalStateException e) {
                // the context is stopping, and Micronaut resolves for its owner on the thread that stops it alone
            }
        }
        BeanRegistration<T> registration = beanContext.getBeanRegistration(type, qualifier);
        keep(registration, false);
        return registration;
    }

    /**
     * A fresh instance of the definition, whatever its scope, which belongs to the owner.
     *
     * @param definition The definition
     * @param <T>        The bean type
     * @return The instance
     */
    public <T> CreatedBean<T> fresh(BeanDefinition<T> definition) {
        BeanDependencyResolver owner = owner();
        if (owner instanceof BeanDependencyGroup own) {
            BeanRegistration<T> registration = own.createBeanRegistration(definition);
            synchronized (this) {
                created.add(registration);
            }
            return registration;
        }
        CreatedBean<T> registration = beanContext.createBeanRegistration(definition);
        hold(registration);
        return registration;
    }

    /**
     * Holds an instance created elsewhere, which is destroyed with the owner's own.
     *
     * @param instance The instance
     */
    public synchronized void hold(CreatedBean<?> instance) {
        held.add(instance);
    }

    /**
     * Whether the owner created anything.
     *
     * @return Whether it did
     */
    public synchronized boolean isEmpty() {
        return created.isEmpty() && held.isEmpty();
    }

    /**
     * The registration of a dependent instance the owner created and has not destroyed yet.
     *
     * @param instance The instance
     * @return The registration, or {@code null}
     */
    public synchronized @Nullable CreatedBean<?> registrationOf(Object instance) {
        for (BeanRegistration<?> registration : created) {
            if (registration.bean() == instance) {
                return registration;
            }
        }
        for (CreatedBean<?> registration : held) {
            if (registration.bean() == instance) {
                return registration;
            }
        }
        return null;
    }

    /**
     * Destroys a dependent instance the owner created, before the owner is done.
     *
     * @param registration The registration it was created with
     * @return Whether the owner still had it, which it does not once it was destroyed
     */
    public boolean destroy(CreatedBean<?> registration) {
        boolean owned;
        synchronized (this) {
            owned = removeSame(created, registration);
            if (!owned) {
                if (removeSame(held, registration)) {
                    registration.close();
                    return true;
                }
                return false;
            }
        }
        BeanDependencyResolver owner = resolver != null ? resolver : currentGroup();
        if (owner == null) {
            registration.close();
        } else {
            // an owner that no longer has it destroyed it already, as its own owner was destroyed
            owner.destroy((BeanRegistration<?>) registration);
        }
        return true;
    }

    private static boolean removeSame(List<? extends CreatedBean<?>> registrations, CreatedBean<?> registration) {
        for (java.util.Iterator<? extends CreatedBean<?>> it = registrations.iterator(); it.hasNext();) {
            if (it.next() == registration) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Destroys what the owner created and Micronaut does not destroy with a bean: its own group, and what it
     * holds itself, every one attempted, after an error as after an exception. What it created through the
     * resolver of the bean it belongs to goes with that bean.
     */
    @Override
    public void close() {
        BeanDependencyGroup closing;
        List<CreatedBean<?>> releasing;
        synchronized (this) {
            closing = group;
            group = null;
            refused = false;
            releasing = List.copyOf(held);
            held.clear();
            if (resolver == null) {
                created.clear();
            }
        }
        Throwable failure = null;
        // in the reverse of the order they were created in, as Micronaut destroys the dependents of a bean: one
        // created later may use one created before it as it is destroyed
        for (CreatedBean<?> registration : releasing.reversed()) {
            try {
                registration.close();
            } catch (RuntimeException | Error e) {
                failure = joined(failure, e);
            }
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (RuntimeException | Error e) {
                failure = joined(failure, e);
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    private static Throwable joined(@Nullable Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    private void keep(BeanRegistration<?> registration, boolean owned) {
        if (!CdiResolution.isDependent(registration.getBeanDefinition())) {
            // a bean of a scope is the scope's
            return;
        }
        synchronized (this) {
            if (owned) {
                created.add(registration);
            } else {
                held.add(registration);
            }
        }
    }

    private synchronized @Nullable BeanDependencyGroup currentGroup() {
        return group;
    }

    /**
     * What creates for the owner: the resolver of the bean it belongs to, or its own group, opened now where
     * Micronaut opens one - or nothing, where it refuses to as the context stops.
     */
    private @Nullable BeanDependencyResolver owner() {
        if (resolver != null) {
            return resolver;
        }
        synchronized (this) {
            if (group == null && !refused) {
                try {
                    group = beanContext.createDependencyGroup();
                } catch (IllegalStateException | UnsupportedOperationException e) {
                    // the context is stopping, and only a listener of its shutdown event opens a group
                    refused = true;
                }
            }
            return group;
        }
    }
}
