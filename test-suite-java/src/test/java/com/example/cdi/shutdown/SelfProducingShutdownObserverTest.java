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
package com.example.cdi.shutdown;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application scoped bean that injects what its own producer method produces, and observes {@code Shutdown},
 * is created as it is first told: its producer is invoked on it through its client proxy while it is being created,
 * as a dependency cycle through a bean of a normal scope allows (section 5.4), and every observer of the shutdown is
 * told.
 */
class SelfProducingShutdownObserverTest {

    /**
     * The beans here are of the contexts of this test alone.
     */
    static final String DEPLOYED = "self-producing-shutdown-observer-test.deployed";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean recording;

    /**
     * What the mill produces.
     *
     * @param name The name
     */
    public record Flour(String name) {
    }

    @ApplicationScoped
    @Requires(property = DEPLOYED)
    public static class Mill {
        @Inject
        Flour flour;

        public String flourName() {
            return flour.name();
        }

        @Produces
        Flour grind() {
            return new Flour("flour");
        }

        void closing(@Observes Shutdown shutdown) {
            if (recording) {
                EVENTS.add("mill told with " + flour.name());
            }
        }

        static void anything(@Observes Object event) {
            if (recording && event instanceof Shutdown) {
                EVENTS.add("anything told");
            }
        }
    }

    @Test
    @Disabled("Micronaut Core registers nothing of a scoped bean between its construction and its injection, so the "
        + "producer its field injection invokes through the client proxy creates the bean again instead of reaching the "
        + "instance under construction (see CONFORMANCE.md, Open points in Micronaut Core)")
    void everyObserverOfTheShutdownIsTold() {
        EVENTS.clear();
        ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true));
        recording = true;
        try {
            context.close();
        } finally {
            recording = false;
        }
        assertEquals(List.of("anything told", "mill told with flour"), EVENTS.stream().sorted().toList());
    }

    @Test
    @Disabled("Micronaut Core registers nothing of a scoped bean between its construction and its injection, so the "
        + "producer its field injection invokes through the client proxy creates the bean again instead of reaching the "
        + "instance under construction (see CONFORMANCE.md, Open points in Micronaut Core)")
    void theBeanIsCreatedWhileTheContainerRuns() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(DEPLOYED, true))) {
            assertEquals("flour", context.getBean(Mill.class).flourName());
        }
    }
}
