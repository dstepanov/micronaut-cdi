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
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jspecify.annotations.Nullable;

/**
 * The creational context a bean is created in, which the specification hands to a program so that it can create a
 * bean itself and say when it is done with it.
 *
 * <p>What the specification uses it for is to hold the instance while it is being created, so that a circular
 * reference between two beans can be resolved to the half-built one, and to keep the dependent instances that were
 * created along with it so that they can be released together. Micronaut resolves the circular reference in its
 * own resolution context, and owns what is created through the context in a dependency group, which releasing the
 * context closes; what is left for this to do is to remember the incomplete instance and to be something to hand
 * back.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiCreationalContext<T> implements CreationalContext<T> {

    private @Nullable T incompleteInstance;
    private final OwnedDependents created;

    /**
     * A creational context of the given container.
     *
     * @param beanContext The context what is created through it is created in
     */
    public CdiCreationalContext(BeanContext beanContext) {
        this.created = OwnedDependents.of(beanContext);
    }

    @Override
    public void push(T incompleteInstance) {
        this.incompleteInstance = incompleteInstance;
    }

    /**
     * What is created through this context, which releasing it destroys along with the dependent instances that
     * were created with it.
     *
     * @return The instances created through it
     */
    public OwnedDependents created() {
        return created;
    }

    /**
     * Whether anything was created through this context.
     *
     * @return Whether anything is tracked
     */
    public boolean hasTracked() {
        return !created.isEmpty();
    }

    @Override
    public void release() {
        incompleteInstance = null;
        // every instance is attempted, the last created first, and the first failure is what comes out; the
        // pre-destroy listeners - the disposer methods of section 3.3.4 - are notified
        created.close();
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
