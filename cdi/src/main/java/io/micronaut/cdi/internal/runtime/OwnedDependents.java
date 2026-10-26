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
     * What was created without Micronaut owning it, destroyed here. What was created through the resolver or the
     * group is Micronaut's, which finds it again from the instance it holds
     * ({@link BeanDependencyResolver#findRegistration}).
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
                return owner.getBeanRegistration(definition, type);
            } catch (IllegalStateException e) {
                // the context is stopping, and Micronaut resolves for its owner on the thread that stops it alone
            }
        }
        BeanRegistration<T> registration = beanContext.getBeanRegistration(definition, type);
        keep(registration);
        return registration;
    }

    /**
     * The registration of a dependent instance of the bean of the given definition, created for the injection point
     * of a lookup (section 2.5.2.5): under a segment of the resolution path that is that point
     * ({@link LookupSegment}), so that the instance reads it off the path as a bean injected there would - the point
     * the lookup was injected into, as the lookup narrowed it, or the lookup itself where it was injected nowhere.
     *
     * <p>The instance is created on behalf of the owner as {@link #registration(BeanDefinition, Argument)} creates
     * it, at the segment ({@link BeanDependencyResolver#getBeanRegistration(BeanDefinition, Argument,
     * io.micronaut.context.BeanResolutionContext.Segment)}): Micronaut resolves it on a path of its own, one per
     * creation, so that two creations do not share a path, with the segment pushed before the instance is resolved.
     * Where the owner can no longer resolve - the context stopping - the instance is created at the segment in a new
     * resolution and held here. A built-in bean of Micronaut's reads the current argument shape of the path, which a
     * segment of this module's does not have, and is created as {@link #registration(BeanDefinition, Argument)}
     * creates it, on a path of its own.</p>
     *
     * @param injectedAt The segment of the injection point the lookup was injected into, or {@code null} for a lookup
     *                   injected nowhere
     * @param selected   The injection point of the lookup
     * @param definition The definition
     * @param type       The type it is asked for as
     * @param <T>        The bean type
     * @return The registration
     */
    public <T> BeanRegistration<T> registrationAt(io.micronaut.context.BeanResolutionContext.@Nullable Segment<?, ?> injectedAt,
                                                  jakarta.enterprise.inject.spi.InjectionPoint selected,
                                                  BeanDefinition<? extends T> definition, Argument<T> type) {
        if (!CdiResolution.isDependent(definition)
            || definition instanceof io.micronaut.inject.provider.AbstractInjectionPointBeanDefinition<?>) {
            return registration(definition, type);
        }
        LookupSegment<?, T> segment = injectedAt != null
            ? LookupSegment.injected(injectedAt, type, selected)
            : LookupSegment.standalone(beanContext.getBeanDefinition(CdiBeanContainer.class), type, selected);
        BeanDependencyResolver owner = owner();
        if (owner != null) {
            try {
                return owner.getBeanRegistration(definition, type, segment);
            } catch (IllegalStateException e) {
                // the context is stopping, and Micronaut resolves for its owner on the thread that stops it alone
            }
        }
        try (io.micronaut.context.BeanResolutionContext resolution =
                 new io.micronaut.context.DefaultBeanResolutionContext(beanContext, null)) {
            BeanRegistration<T> registration = resolution.getBeanRegistration(definition, type, segment);
            keep(registration);
            return registration;
        }
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
        if (resolver == null) {
            keep(registration);
        }
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
                return owner.getBeanRegistration(type, qualifier);
            } catch (IllegalStateException e) {
                // the context is stopping, and Micronaut resolves for its owner on the thread that stops it alone
            }
        }
        BeanRegistration<T> registration = beanContext.getBeanRegistration(type, qualifier);
        keep(registration);
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
            return own.createBeanRegistration(definition);
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
     * Whether the owner created anything: it opened a group of its own, or holds what Micronaut does not own.
     *
     * @return Whether it did
     */
    public synchronized boolean isEmpty() {
        return group == null && held.isEmpty();
    }

    /**
     * The registration of a dependent instance the owner created and has not destroyed yet: the one Micronaut
     * holds for it, by the identity of the instance, or the one held here.
     *
     * @param instance The instance
     * @return The registration, or {@code null}
     */
    public @Nullable CreatedBean<?> registrationOf(Object instance) {
        BeanDependencyResolver owner = resolver != null ? resolver : currentGroup();
        if (owner != null) {
            BeanRegistration<Object> owned = owner.findRegistration(instance).orElse(null);
            if (owned != null) {
                return owned;
            }
        }
        synchronized (this) {
            for (CreatedBean<?> registration : held) {
                if (registration.bean() == instance) {
                    return registration;
                }
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
        synchronized (this) {
            if (held.removeIf(one -> one == registration)) {
                registration.close();
                return true;
            }
        }
        BeanDependencyResolver owner = resolver != null ? resolver : currentGroup();
        // an owner that no longer has it destroyed it already, as its own owner was destroyed
        return owner != null && registration instanceof BeanRegistration<?> owned && owner.destroy(owned);
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

    /**
     * Holds an instance created for the owner that Micronaut does not own.
     */
    private void keep(BeanRegistration<?> registration) {
        if (registration.hasOwnLifecycle()) {
            // a bean of a scope is the scope's, a singleton the context's: only an instance created for the lookup
            // lives until its registration is closed
            hold(registration);
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
