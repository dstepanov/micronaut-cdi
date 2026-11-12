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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code Registration} type that extends {@code TypeLiteral} may hold raw types and wildcards among its type
 * arguments (CDI 5.0, section 12.4): a bean matches where its set of bean types contains the expected type, and an
 * observer where its observed event type is assignable to it. A bean type holds no wildcard (section 2.2.1), so a
 * wildcard matters to observers.
 */
class RegistrationWildcardTypeLiteralTest {

    static final List<String> DESCRIBED = new ArrayList<>();

    private static final String SOURCE = """
        package wildcards;

        import jakarta.enterprise.event.Observes;
        import java.util.List;

        @jakarta.enterprise.context.Dependent
        public class Producers {

            @jakarta.enterprise.inject.Produces
            List<Class<String>> stringClasses() {
                return List.of();
            }

            @SuppressWarnings("rawtypes")
            @jakarta.enterprise.inject.Produces
            List<Class> rawClasses() {
                return List.of();
            }

            void integers(@Observes List<Integer> event) {
            }

            void strings(@Observes List<String> event) {
            }
        }
        """;

    @Test
    void wildcardAndRawTypeArgumentsMatch() {
        DESCRIBED.clear();
        try (JavaParser parser = ExtensionCompilation.of(Wildcards.class)) {
            parser.generate("wildcards.Producers", SOURCE);
        }
        assertEquals(List.of("bean rawClasses", "observer integers"),
            DESCRIBED.stream().sorted().toList());
    }

    /**
     * The registration methods.
     */
    public static final class Wildcards implements BuildCompatibleExtension {
        @Registration(types = ListOfRawClass.class)
        public void raw(BeanInfo bean) {
            DESCRIBED.add("bean " + bean.producerMethod().name());
        }

        @Registration(types = ListOfNumbers.class)
        public void numbers(ObserverInfo observer) {
            DESCRIBED.add("observer " + observer.observerMethod().name());
        }
    }

    /**
     * A list of raw classes.
     */
    @SuppressWarnings("rawtypes")
    public static final class ListOfRawClass extends TypeLiteral<List<Class>> {
    }

    /**
     * A list of some kind of number.
     */
    public static final class ListOfNumbers extends TypeLiteral<List<? extends Number>> {
    }
}
