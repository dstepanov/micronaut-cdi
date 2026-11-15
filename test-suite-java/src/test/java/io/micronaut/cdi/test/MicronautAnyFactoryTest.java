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
import io.micronaut.context.Qualifier;
import io.micronaut.context.annotation.Any;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.naming.Named;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A bean of Micronaut's own declared with Micronaut's {@code @Any} is created for the qualifier it is asked by: a
 * point of a bean of the specification left to Micronaut's rules hands the factory the qualifier of the point, and
 * shares the instance Micronaut's own lookup by that qualifier finds.
 */
class MicronautAnyFactoryTest {

    @Test
    void anAnyFactoryIsHandedTheQualifierOfEachPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Garage garage = context.getBean(Garage.class);
            assertEquals("v8", garage.v8.name());
            assertEquals("v6", garage.v6.name());
            assertNotSame(garage.v8, garage.v6);
            assertSame(context.getBean(Engine.class, Qualifiers.byName("v8")), garage.v8);
            assertSame(context.getBean(Engine.class, Qualifiers.byName("v6")), garage.v6);
        }
    }

    public record Engine(String name) {
    }

    @Factory
    public static class Engines {

        @Any
        @Bean
        @Singleton
        Engine engine(Qualifier<Engine> qualifier) {
            return new Engine(qualifier instanceof Named named ? named.getName() : String.valueOf(qualifier));
        }
    }

    @Dependent
    public static class Garage {
        @Inject
        @jakarta.inject.Named("v8")
        Engine v8;

        @Inject
        @jakarta.inject.Named("v6")
        Engine v6;
    }
}
