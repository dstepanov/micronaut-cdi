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
package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disposer that is not public, declared on a bean in a normal scope, disposes of what its producer produced
 * and reaches an instance holding the declaring bean's state.
 *
 * <p>Whether a disposer is public is read from what was compiled rather than by reflecting on the method. Note
 * that this test does not by itself distinguish the branch that decision feeds — the call arrives at an
 * instance with its state either way here — so it guards the disposal, not the choice between the client proxy
 * and the instance behind it. That choice has no test in this suite or in the kit.</p>
 */
class NonPublicDisposerTest {

    /**
     * What the producer produces.
     */
    record Handle(String id) {
    }

    /**
     * Produces in a normal scope, so that a client proxy stands in front of it, and disposes of what it
     * produced with a method that is not public.
     */
    @ApplicationScoped
    static class Handles {

        static final List<String> DISPOSED = new ArrayList<>();

        private final String owner = "held";

        @Produces
        @Dependent
        Handle handle() {
            return new Handle("h-1");
        }

        // package private on purpose: a client proxy does not delegate it
        void release(@Disposes Handle handle) {
            // reads its own state, which only the instance behind the proxy has
            DISPOSED.add(handle.id() + ":" + owner);
        }
    }

    /**
     * Holds the produced instance so that it is disposed of when the application scope ends.
     */
    @ApplicationScoped
    static class Holder {

        @Inject
        Handle handle;

        String use() {
            return handle.id();
        }
    }

    @Test
    void aDisposerThatIsNotPublicDisposesOfWhatItsProducerProduced() {
        Handles.DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("h-1", context.getBean(Holder.class).use());
        }
        assertTrue(Handles.DISPOSED.contains("h-1:held"),
            "the non-public disposer should have run against the instance holding the state, got "
                + Handles.DISPOSED);
    }
}
