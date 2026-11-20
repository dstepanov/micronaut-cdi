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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dependent instance injected into a parameter of a producer method is a dependent object of the bean it produces
 * (CDI 4.1 section 6.4.1), and is destroyed when that bean is, through {@code Instance.destroy} as through a handle.
 * A dependent bean whose superclass declares a producer method of a normal scope is destroyed too: it inherits no
 * producer (section 4.2), and so no client proxy of the product, which made Micronaut build it as a proxy whose own
 * {@code PreDestroy} never ran.
 */
class ProducerParameterDependentTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * Handed to the producer method.
     */
    @Dependent
    public static class Ink {
        @PreDestroy
        void destroyed() {
            EVENTS.add("ink destroyed");
        }
    }

    /**
     * What the producer makes.
     */
    public static final class Letter {
    }

    /**
     * Produces letters with the ink it is handed.
     */
    @Dependent
    public static class Press {
        @Produces
        Letter print(Ink ink) {
            return new Letter();
        }
    }

    /**
     * Produces envelopes; the paper extends it, and inherits no producer (section 4.2).
     */
    @jakarta.enterprise.context.ApplicationScoped
    public static class Stationery {
        @Produces
        @jakarta.enterprise.context.ApplicationScoped
        Envelope envelope() {
            return new Envelope();
        }
    }

    /**
     * What the stationery produces.
     */
    public static class Envelope {
    }

    /**
     * A dependent subclass of a class with a producer method of a normal scope.
     */
    @Dependent
    public static class Paper extends Stationery {
        @PreDestroy
        void destroyed() {
            EVENTS.add("paper destroyed");
        }
    }

    /**
     * What the press below makes.
     */
    public static final class Leaflet {
    }

    /**
     * Produces leaflets on the paper it is handed.
     */
    @Dependent
    public static class PaperPress {
        @Produces
        Leaflet print(Paper paper) {
            return new Leaflet();
        }
    }

    @Test
    void aDependentSubclassOfAProducingClassIsDestroyed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Paper> papers = context.getBean(CdiBeanContainer.class).createInstance().select(Paper.class);
            EVENTS.clear();
            papers.destroy(papers.get());
            assertEquals(List.of("paper destroyed"), EVENTS);
        }
    }

    @Test
    void aDependentSubclassOfAProducingClassIsDestroyedWithTheProductItIsHandedTo() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Leaflet> leaflets = context.getBean(CdiBeanContainer.class).createInstance().select(Leaflet.class);
            EVENTS.clear();
            leaflets.destroy(leaflets.get());
            assertEquals(List.of("paper destroyed"), EVENTS);
        }
    }

    @Test
    void theDependentParameterOfAProducerIsDestroyedWithItsProduct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Letter> letters = context.getBean(CdiBeanContainer.class).createInstance().select(Letter.class);
            EVENTS.clear();
            letters.destroy(letters.get());
            assertEquals(List.of("ink destroyed"), EVENTS);
            EVENTS.clear();
            Instance.Handle<Letter> handle = letters.getHandle();
            handle.get();
            handle.destroy();
            assertEquals(List.of("ink destroyed"), EVENTS);
        }
    }
}
