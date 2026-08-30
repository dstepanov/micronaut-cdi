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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two static observer methods of the same name and arity are two observers: each is identified by its full
 * signature, and each receives its own event.
 */
class StaticObserverOverloadTest {

    @Test
    void sameNameStaticObserverOverloadsEachReceiveTheirOwnEvent() {
        try (ApplicationContext context = ApplicationContext.run()) {
            OverloadedObservers.SEEN.clear();
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Brew.class).fire(new Brew("espresso"));
            container.getEvent().select(Leaf.class).fire(new Leaf("sencha"));
            assertEquals(List.of("brew:espresso", "leaf:sencha"), OverloadedObservers.SEEN);
        }
    }

    /**
     * One event kind.
     *
     * @param name The brew
     */
    public record Brew(String name) {
    }

    /**
     * The other event kind.
     *
     * @param name The leaf
     */
    public record Leaf(String name) {
    }

    /**
     * Declares the two same-name, same-arity static observers.
     */
    @Dependent
    public static class OverloadedObservers {

        /**
         * What the observers were notified of, in order.
         */
        public static final List<String> SEEN = new CopyOnWriteArrayList<>();

        static void observe(@Observes Brew brew) {
            SEEN.add("brew:" + brew.name());
        }

        static void observe(@Observes Leaf leaf) {
            SEEN.add("leaf:" + leaf.name());
        }
    }
}
