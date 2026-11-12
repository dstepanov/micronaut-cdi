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

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection point registered for a synthetic bean is held to the rules of an injection point a class declares.
 */
class SyntheticInjectionPointRulesTest {

    private static final String SOURCE = """
        package ruled;

        public class Anchor {
        }
        """;

    @Test
    void aRawEventIsADefinitionError() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> compile(RawEvent.class));
        assertTrue(failure.getMessage().contains("a raw jakarta.enterprise.event.Event names no type"),
            failure.getMessage());
    }

    @Test
    void theInjectionPointOfABeanThatIsNotDependentIsADefinitionError() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> compile(ScopedInjectionPoint.class));
        assertTrue(failure.getMessage().contains("only a dependent bean"), failure.getMessage());
    }

    @Test
    void theInjectionPointOfADependentBeanIsLegal() {
        assertDoesNotThrow(() -> compile(DependentInjectionPoint.class));
    }

    private static void compile(Class<? extends BuildCompatibleExtension> extension) {
        try (JavaParser parser = ExtensionCompilation.of(extension)) {
            parser.generate("ruled.Anchor", SOURCE);
        }
    }

    /**
     * Registers a raw Event.
     */
    public static final class RawEvent implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Ruled.class).type(Ruled.class).scope(Dependent.class)
                .withInjectionPoint(Event.class)
                .createWith(RuledCreator.class);
        }
    }

    /**
     * Registers the default InjectionPoint of an application scoped bean.
     */
    public static final class ScopedInjectionPoint implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Ruled.class).type(Ruled.class).scope(ApplicationScoped.class)
                .withInjectionPoint(InjectionPoint.class)
                .createWith(RuledCreator.class);
        }
    }

    /**
     * Registers the default InjectionPoint of a dependent bean.
     */
    public static final class DependentInjectionPoint implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Ruled.class).type(Ruled.class).scope(Dependent.class)
                .withInjectionPoint(InjectionPoint.class)
                .createWith(RuledCreator.class);
        }
    }

    /**
     * Creates the bean.
     */
    public static final class RuledCreator implements SyntheticBeanCreator<Ruled> {
        @Override
        public Ruled create(SyntheticInjections injections, Parameters params) {
            return new Ruled();
        }
    }

    /**
     * The synthetic bean.
     */
    public static class Ruled {
    }
}
