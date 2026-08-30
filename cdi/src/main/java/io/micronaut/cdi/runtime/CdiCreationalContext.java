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
import jakarta.enterprise.context.spi.CreationalContext;
import org.jspecify.annotations.Nullable;

/**
 * The creational context a bean is created in, which the specification hands to a program so that it can create a
 * bean itself and say when it is done with it.
 *
 * <p>It carries almost nothing here. What the specification uses it for is to hold the instance while it is being
 * created, so that a circular reference between two beans can be resolved to the half-built one, and to keep the
 * dependent instances that were created along with it so that they can be released together. Micronaut does both
 * of those things in its own resolution context, which is already under way by the time a bean is created, so
 * what is left for this to do is to remember the incomplete instance and to be something to hand back.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiCreationalContext<T> implements CreationalContext<T> {

    private final io.micronaut.context.@Nullable BeanContext beanContext;
    private @Nullable T incompleteInstance;
    private final java.util.List<io.micronaut.context.BeanRegistration<?>> tracked = new java.util.ArrayList<>(2);

    public CdiCreationalContext() {
        this(null);
    }

    public CdiCreationalContext(io.micronaut.context.@Nullable BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public void push(T incompleteInstance) {
        this.incompleteInstance = incompleteInstance;
    }

    /**
     * Remembers what was created in this context, so that releasing the context destroys it along with the
     * dependent instances that were created with it.
     *
     * @param registration The registration of what was created
     */
    public void track(io.micronaut.context.BeanRegistration<?> registration) {
        tracked.add(registration);
    }

    /**
     * Whether anything was created through this context.
     *
     * @return Whether anything is tracked
     */
    public boolean hasTracked() {
        return !tracked.isEmpty();
    }

    @Override
    public void release() {
        incompleteInstance = null;
        java.util.List<io.micronaut.context.BeanRegistration<?>> toClose = java.util.List.copyOf(tracked);
        tracked.clear();
        // one throwing @PreDestroy must not leave the rest undestroyed
        RuntimeException failure = null;
        for (io.micronaut.context.BeanRegistration<?> registration : toClose) {
            try {
                if (beanContext != null) {
                    // the registration's own close is a no-op for a definition with nothing of its own to
                    // dispose: destruction that has to reach the pre-destroy listeners — the disposer methods
                    // of section 3.3.4 — goes through the context
                    destroy(beanContext, registration);
                } else {
                    registration.close();
                }
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

    private static <B> void destroy(io.micronaut.context.BeanContext beanContext,
                                    io.micronaut.context.BeanRegistration<B> registration) {
        beanContext.destroyBean(registration);
    }

    /**
     * The instance that was pushed while it was being created, if one was.
     *
     * @return The incomplete instance, or {@code null}
     */
    public @Nullable T incompleteInstance() {
        return incompleteInstance;
    }
}
