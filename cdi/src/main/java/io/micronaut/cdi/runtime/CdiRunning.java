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

    private CdiRunning() {
    }

    static void started(CdiBeanContainer container) {
        RUNNING.add(container);
    }

    static void stopped(CdiBeanContainer container) {
        RUNNING.remove(container);
    }

    /**
     * The container the static entry point resolves to.
     *
     * @return The most recently started container, or {@code null} when none is running
     */
    public static @Nullable CdiBeanContainer current() {
        if (RUNNING.isEmpty()) {
            return null;
        }
        return RUNNING.get(RUNNING.size() - 1);
    }
}
