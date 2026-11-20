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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Notifying the observer method a dependent bean declares and its subclass inherits creates the instances it is
 * notified on, and nothing they produce: the disposer of a producer field of the bean runs only for what that field
 * produced.
 */
class InheritedObserverDisposerTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * The event observed.
     */
    public record Knock() {
    }

    /**
     * What the field produces.
     *
     * @param name The name
     */
    public record Parcel(String name) {
    }

    /**
     * Produces through a field, disposes of what it produced, and observes.
     */
    @Dependent
    public static class Porch {
        @Produces
        Parcel parcel = new Parcel("parcel");

        void dispose(@Disposes Parcel parcel) {
            if (recording) {
                EVENTS.add("disposed " + parcel.name());
            }
        }

        void knocked(@Observes Knock knock) {
            if (recording) {
                EVENTS.add("knocked at " + name());
            }
        }

        String name() {
            return "porch";
        }
    }

    /**
     * Inherits the observer method, and declares no producer.
     */
    @Dependent
    public static class BackPorch extends Porch {
        @Override
        String name() {
            return "back porch";
        }
    }

    static volatile boolean recording;

    @Test
    void notifyingTheObserversDisposesOfNothing() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            recording = true;
            context.getBean(CdiBeanContainer.class).getEvent().select(Knock.class).fire(new Knock());
            recording = false;
        } finally {
            recording = false;
        }
        assertEquals(List.of("knocked at back porch", "knocked at porch"), EVENTS.stream().sorted().toList());
    }
}
