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
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands the static entry point of the specification the container that is running.
 *
 * <p>It is found the way the specification says a provider is found, through the service loader, so that a
 * program which calls {@code CDI.current()} reaches the container of this module without naming it; a container
 * that starts installs one with {@code CDI.setCDIProvider} where none of this module is the specification's
 * provider yet ({@link MicronautCDI#installedProvider()}), and registers with that one.</p>
 *
 * <p>The provider holds the containers that are running, so that the static entry point has one to resolve to: a
 * container registers itself as it starts and takes itself off as it shuts down. More than one can run at once - a
 * test that starts a container per test does exactly that - so they are kept in order and the most recently started
 * is the current one, a choice rather than something the specification says, which describes one container per
 * application. Nothing of the container itself reads this; it is only for a caller of the entry point, which has
 * nothing else. The one static is the specification's own: the provider its API holds.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public final class MicronautCDIProvider implements CDIProvider {

    private final List<CdiBeanContainer> running = new CopyOnWriteArrayList<>();

    void started(CdiBeanContainer container) {
        running.add(container);
    }

    void stopped(CdiBeanContainer container) {
        running.remove(container);
    }

    void stoppedFor(BeanContext beanContext) {
        running.removeIf(container -> container.beanContext() == beanContext);
    }

    /**
     * The container the static entry point resolves to.
     *
     * @return The most recently started container, or {@code null} when none is running
     */
    @Nullable CdiBeanContainer current() {
        // one snapshot: a container going down on another thread must not turn the read into an index error
        Object[] containers = running.toArray();
        return containers.length == 0 ? null : (CdiBeanContainer) containers[containers.length - 1];
    }

    @Override
    public CDI<Object> getCDI() {
        CdiBeanContainer container = current();
        if (container == null) {
            throw new IllegalStateException("No container is running. A container of this module is a Micronaut "
                + "bean context, and CDI.current() resolves to one only while it is running");
        }
        return new MicronautCDI(container);
    }
}
