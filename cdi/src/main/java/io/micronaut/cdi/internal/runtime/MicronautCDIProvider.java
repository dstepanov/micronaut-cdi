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
import io.micronaut.core.io.service.SoftServiceLoader;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands the static entry point of the specification the container that is running.
 *
 * <p>The specification's API finds providers through the service loader, and makes a new instance of each every time
 * it looks: an instance it finds holds no container, and only the instance a container registered with can answer.
 * A container that starts therefore installs one with {@code CDI.setCDIProvider} where the API holds none of this
 * module ({@link MicronautCDI#installedProvider()}), and registers with it. A provider a program set itself is left
 * in place; one the API found itself, of another implementation, is replaced, as only an installed instance reaches
 * the containers of this module without a static of its own. The service entry of this class stays: an instance the
 * API finds before any container started hands over as below, and a container that starts registers with it.</p>
 *
 * <p>The API never leaves a provider set with {@code setCDIProvider}, so this one hands over itself: while none of
 * its containers runs, it answers with the first of the other providers the service loader finds, by their priority,
 * that answers, as the API would have picked one, and fails as the API does where none answers.</p>
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
     * The container the static entry point resolves to. A container whose context failed to start is never it: a
     * context Micronaut failed to start is not stopped unless its starter cleans it up, so its container never takes
     * itself off, and is let go of here instead. One whose context is still starting is.
     *
     * @return The most recently started container, or {@code null} when none is running
     */
    @Nullable CdiBeanContainer current() {
        // one snapshot: a container going down on another thread must not turn the read into an index error
        Object[] containers = running.toArray();
        for (int i = containers.length - 1; i >= 0; i--) {
            CdiBeanContainer container = (CdiBeanContainer) containers[i];
            if (!failedToStart(container.beanContext())) {
                return container;
            }
            running.remove(container);
        }
        return null;
    }

    /**
     * Whether the start of a context failed: it does not run, and is not starting either, which Micronaut's own
     * context tells apart ({@link DefaultBeanContext#isStoppingOrStopped()}). A context that stopped takes its
     * container off as it stops.
     */
    private static boolean failedToStart(BeanContext beanContext) {
        return !beanContext.isRunning() && beanContext instanceof DefaultBeanContext context
            && context.isStoppingOrStopped();
    }

    @Override
    public CDI<Object> getCDI() {
        CdiBeanContainer container = current();
        if (container != null) {
            return new MicronautCDI(container);
        }
        CDI<Object> other = otherImplementation();
        if (other != null) {
            return other;
        }
        throw new IllegalStateException("No container is running. A container of this module is a Micronaut "
            + "bean context, and CDI.current() resolves to one only while it is running");
    }

    /**
     * What the first provider of another implementation that answers resolves to, the providers taken as the
     * specification's API finds them: through the service loader of the API's class loader, by descending priority.
     * A provider of this module - this class, from whichever class loader - is passed over: it holds no container,
     * and would hand over in turn.
     *
     * @return The entry point of another implementation, or {@code null} where none answers
     */
    private static @Nullable CDI<Object> otherImplementation() {
        // the one service loading of the runtime, and no reflection of its own: the specification's API finds its
        // providers this way, and the hand-over finds the ones it would have found
        @SuppressWarnings("NoReflection")
        List<CDIProvider> providers = SoftServiceLoader.load(CDIProvider.class, CDI.class.getClassLoader())
            .collectAll(provider -> !provider.getClass().getName().equals(MicronautCDIProvider.class.getName()));
        providers.sort(Comparator.comparingInt(CDIProvider::getPriority).reversed());
        for (CDIProvider provider : providers) {
            try {
                CDI<Object> cdi = provider.getCDI();
                if (cdi != null) {
                    return cdi;
                }
            } catch (IllegalStateException e) {
                // what a provider says when its implementation runs no container either, as the API takes it
            }
        }
        return null;
    }
}
