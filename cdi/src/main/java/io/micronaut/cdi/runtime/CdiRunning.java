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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The containers that are running, so that the static entry point of the specification has one to resolve to.
 *
 * <p>{@code CDI.current()} is a static method, and a program that calls it has nothing to hand it. Something has
 * to know which container is running, and this is it: a container registers itself as it starts and takes itself
 * off as it shuts down.</p>
 *
 * <p>More than one can run at once — a test that starts a container per test does exactly that — so the ones that
 * are running are kept in order and the most recently started is the current one. That is a choice rather than
 * something the specification says, which describes one container per application.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiRunning {

    private static final List<CdiBeanContainer> RUNNING = new CopyOnWriteArrayList<>();
    private static final List<BeanContext> CONTEXTS = new CopyOnWriteArrayList<>();

    private CdiRunning() {
    }

    static void started(CdiBeanContainer container) {
        RUNNING.add(container);
        starting(container.beanContext());
    }

    static void stopped(CdiBeanContainer container) {
        RUNNING.remove(container);
        CONTEXTS.remove(container.beanContext());
    }

    /**
     * Makes a bean context known while its container is still coming up: the infrastructure of a container
     * fires the lifecycle events of the application before the container itself is there to be asked.
     *
     * @param beanContext The bean context of a container that is starting
     */
    static void starting(BeanContext beanContext) {
        if (!CONTEXTS.contains(beanContext)) {
            CONTEXTS.add(beanContext);
        }
    }

    /**
     * Forgets a bean context whose container is going down, or never came up.
     *
     * @param beanContext The bean context
     */
    static void stopped(BeanContext beanContext) {
        CONTEXTS.remove(beanContext);
    }

    /**
     * The bean context of the container that is current, which may still be starting.
     *
     * @return The most recently started bean context, or {@code null} when there is none
     */
    public static @Nullable BeanContext currentContext() {
        // one snapshot, as for the containers
        Object[] contexts = CONTEXTS.toArray();
        return contexts.length == 0 ? null : (BeanContext) contexts[contexts.length - 1];
    }

    /**
     * The container the static entry point resolves to.
     *
     * @return The most recently started container, or {@code null} when none is running
     */
    public static @Nullable CdiBeanContainer current() {
        // one snapshot: a container going down on another thread must not turn the read into an index error
        Object[] running = RUNNING.toArray();
        return running.length == 0 ? null : (CdiBeanContainer) running[running.length - 1];
    }
}
