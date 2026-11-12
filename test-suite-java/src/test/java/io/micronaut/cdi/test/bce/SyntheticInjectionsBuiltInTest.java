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
package io.micronaut.cdi.test.bce;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A built-in {@code Event} obtained through {@code SyntheticInjections} carries the qualifiers its injection point was
 * registered with, as an injected one carries the qualifiers of its point.
 */
class SyntheticInjectionsBuiltInTest {

    static final List<String> HEARD = new ArrayList<>();

    private static final String SOURCE = """
        package firing;

        public class Anchor {
        }
        """;

    @Test
    void anEventObtainedThroughSyntheticInjectionsFiresWithItsQualifiers() {
        HEARD.clear();
        try (ApplicationContext context = ExtensionDeployment.start(FiringExtension.class, "firing.Anchor", SOURCE)) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            lookup.select(Firing.class).get();
        }
        assertEquals(List.of("loud created"), HEARD);
    }

    @Test
    void anEventLookedUpWithAQualifierFiresWithIt() {
        HEARD.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            lookup.select(new TypeLiteral<Event<String>>() { }, Loud.Literal.INSTANCE).get().fire("looked up");
        }
        assertEquals(List.of("loud looked up"), HEARD);
    }

    /**
     * The qualifier the event is fired with.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Loud {

        /**
         * The literal.
         */
        final class Literal extends AnnotationLiteral<Loud> implements Loud {
            static final Literal INSTANCE = new Literal();
        }
    }

    /**
     * Hears what is fired loud.
     */
    @Dependent
    public static class Listener {
        void heard(@Observes @Loud String event) {
            HEARD.add("loud " + event);
        }
    }

    /**
     * Adds the synthetic bean, with an injection point of a loud event.
     */
    public static final class FiringExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components, Types types) {
            components.addBean(Firing.class)
                .type(Firing.class)
                .scope(Dependent.class)
                .withInjectionPoint(types.parameterized(Event.class, String.class), Loud.Literal.INSTANCE)
                .createWith(FiringCreator.class);
        }
    }

    /**
     * Fires a loud event as the bean is created.
     */
    public static final class FiringCreator implements SyntheticBeanCreator<Firing> {
        @Override
        public Firing create(SyntheticInjections injections, Parameters params) {
            injections.get(new TypeLiteral<Event<String>>() { }, Loud.Literal.INSTANCE).fire("created");
            return new Firing();
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Firing {
    }
}
