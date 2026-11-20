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
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Has the container take itself down before Micronaut destroys the first bean of a context that is stopping, where
 * the shutdown event did not get to the container: a listener of that event told before it threw, and Micronaut tells
 * no listener after one that throws. Micronaut destroys a listener of the destruction of beans after the beans it
 * listens to, so this one is told of the first, while every singleton the requests still open use is still there.
 *
 * <p>It is a bean of its own, with nothing to depend on but the bean context, rather than the lifecycle bean itself:
 * Micronaut creates a listener of the destruction of every bean as it first tells one, which would create the lifecycle
 * bean out of its order. The lifecycle bean hands itself over as it is created.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ContainerStopGuard implements BeanPreDestroyEventListener<Object> {

    private final BeanContext beanContext;
    private volatile @Nullable ContainerLifecycle lifecycle;

    public ContainerStopGuard(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    void guard(ContainerLifecycle containerLifecycle) {
        this.lifecycle = containerLifecycle;
    }

    @Override
    public Object onPreDestroy(BeanPreDestroyEvent<Object> event) {
        ContainerLifecycle guarded = lifecycle;
        // only as the context stops: a dependent instance released while it runs is destroyed through here as well
        if (guarded != null && beanContext instanceof DefaultBeanContext context && context.isStoppingOrStopped()) {
            guarded.beforeTheFirstBeanIsDestroyed();
        }
        return event.getBean();
    }
}
