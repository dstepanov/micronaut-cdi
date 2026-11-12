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
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code SyntheticInjections} hands out the type an injection point was registered with, and no other: a wildcard
 * the type would be assignable to is another type.
 */
class SyntheticInjectionsSameTypeTest {

    static final List<String> LOOKED_UP = new ArrayList<>();

    private static final String SOURCE = """
        package typed;

        @jakarta.enterprise.context.Dependent
        public class Numbers {

            @jakarta.enterprise.inject.Produces
            java.util.List<Number> numbers() {
                return java.util.List.of(1);
            }
        }
        """;

    @Test
    void onlyTheRegisteredTypeIsHandedOut() {
        LOOKED_UP.clear();
        try (ApplicationContext context = ExtensionDeployment.start(TypedExtension.class, "typed.Numbers", SOURCE)) {
            context.getBean(CdiBeanContainer.class).createInstance().select(Typed.class).get();
        }
        assertEquals(List.of("registered", "refused"), LOOKED_UP);
    }

    /**
     * Registers List of Number.
     */
    public static final class TypedExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components, Types types) {
            components.addBean(Typed.class).type(Typed.class).scope(Dependent.class)
                .withInjectionPoint(types.parameterized(List.class, Number.class))
                .createWith(TypedCreator.class);
        }
    }

    /**
     * Looks up the registered type, and a wildcard it is assignable to.
     */
    public static final class TypedCreator implements SyntheticBeanCreator<Typed> {
        @Override
        public Typed create(SyntheticInjections injections, Parameters params) {
            injections.get(new TypeLiteral<List<Number>>() { });
            LOOKED_UP.add("registered");
            try {
                injections.get(new TypeLiteral<List<? extends Number>>() { });
                LOOKED_UP.add("handed out");
            } catch (IllegalArgumentException e) {
                LOOKED_UP.add("refused");
            }
            return new Typed();
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Typed {
    }
}
