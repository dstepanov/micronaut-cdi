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
 * A static disposer disposes of what a producer produced without an instance of the class that declares it:
 * it is invoked through the executable method compiled for it, the same way an instance disposer is, and
 * whatever else it asks for is resolved as an injection point.
 */
class StaticDisposerTest {

    /**
     * What the producer produces.
     */
    record Ticket(String id) {
    }

    /**
     * What a static disposer asks for beside the instance it disposes of.
     */
    @ApplicationScoped
    static class Auditor {

        private final List<String> audited = new ArrayList<>();

        void audit(String id) {
            audited.add(id);
        }

        List<String> audited() {
            return audited;
        }
    }

    /**
     * Declares both a producer and a static disposer for what it produces.
     */
    @ApplicationScoped
    static class Tickets {

        static final List<String> DISPOSED = new ArrayList<>();

        static int instances;

        Tickets() {
            instances++;
        }

        @Produces
        @Dependent
        Ticket ticket() {
            return new Ticket("t-1");
        }

        static void close(@Disposes Ticket ticket, Auditor auditor) {
            DISPOSED.add(ticket.id());
            auditor.audit(ticket.id());
        }
    }

    /**
     * Holds the produced instance so that it is disposed of when the application scope ends.
     */
    @ApplicationScoped
    static class Holder {

        @Inject
        Ticket ticket;

        String use() {
            return ticket.id();
        }
    }

    @Test
    void aStaticDisposerRunsWithItsOtherParametersResolved() {
        Tickets.DISPOSED.clear();
        Tickets.instances = 0;
        List<String> audited;
        int whileRunning;
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("t-1", context.getBean(Holder.class).use());
            audited = context.getBean(Auditor.class).audited();
            // whatever producing took — a client proxy of a normal scoped bean is an instance of its class too
            whileRunning = Tickets.instances;
        }
        assertTrue(Tickets.DISPOSED.contains("t-1"),
            "the static disposer should have run, got " + Tickets.DISPOSED);
        assertTrue(audited.contains("t-1"),
            "the disposer's other parameter should have been resolved, got " + audited);
        assertEquals(whileRunning, Tickets.instances,
            "a static disposer needs no instance of the class declaring it, so the disposal creates none; "
                + "had " + whileRunning + " before it and " + Tickets.instances + " after");
    }
}
