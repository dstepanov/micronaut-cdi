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
package org.example.cdi.se;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An SE container still runs while the observers of {@code Shutdown} are told it shuts down: they may look beans up
 * through it. Once {@code close()} returns it no longer runs, and a second close is the caller's error.
 */
class SeContainerRunsDuringShutdownTest {

    static final AtomicReference<SeContainer> CONTAINER = new AtomicReference<>();
    static final AtomicReference<Boolean> RUNNING = new AtomicReference<>();
    static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();

    @ApplicationScoped
    public static class Watching {
        void onShutdown(@Observes Shutdown shutdown) {
            SeContainer container = CONTAINER.get();
            if (container == null) {
                return;
            }
            try {
                RUNNING.set(container.isRunning());
                container.select(Watching.class).get();
            } catch (Throwable t) {
                FAILURE.set(t);
            }
        }
    }

    @Test
    void theContainerRunsWhileShutdownIsObserved() {
        SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Watching.class).initialize();
        CONTAINER.set(container);
        try {
            container.close();
        } finally {
            CONTAINER.set(null);
        }
        assertNull(FAILURE.get());
        assertEquals(Boolean.TRUE, RUNNING.get());
        assertFalse(container.isRunning());
        assertThrows(IllegalStateException.class, container::close);
    }
}
