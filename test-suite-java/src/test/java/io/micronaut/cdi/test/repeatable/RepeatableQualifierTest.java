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
package io.micronaut.cdi.test.repeatable;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A qualifier written once, and one written twice, each narrow an observer to the events qualified that way
 * (section 2.1.3).
 */
class RepeatableQualifierTest {

    @Test
    void aRepeatableQualifierNarrowsTheObserversItWasWrittenOn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Watcher.A.set(0);
            Watcher.B.set(0);
            Watcher.BC.set(0);
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Signal.class, new Start.Literal("A")).fire(new Signal());
            container.getEvent().select(Signal.class, new Start.Literal("B")).fire(new Signal());
            container.getEvent().select(Signal.class,
                new Start.Literal("B"), new Start.Literal("C")).fire(new Signal());
            // the singly-qualified observer hears only its own event
            assertEquals(1, Watcher.A.get());
            // the B observer hears the B event and the one qualified B and C
            assertEquals(2, Watcher.B.get());
            // and the doubly-qualified one hears only the event carrying both
            assertEquals(1, Watcher.BC.get());
        }
    }

    /**
     * What is fired.
     */
    public static class Signal {
    }

    /**
     * The observers, one per qualification.
     */
    @Dependent
    public static class Watcher {

        static final AtomicInteger A = new AtomicInteger();
        static final AtomicInteger B = new AtomicInteger();
        static final AtomicInteger BC = new AtomicInteger();

        void onA(@Observes @Start("A") Signal signal) {
            A.incrementAndGet();
        }

        void onB(@Observes @Start("B") Signal signal) {
            B.incrementAndGet();
        }

        void onBC(@Observes @Start("B") @Start("C") Signal signal) {
            BC.incrementAndGet();
        }
    }
}
