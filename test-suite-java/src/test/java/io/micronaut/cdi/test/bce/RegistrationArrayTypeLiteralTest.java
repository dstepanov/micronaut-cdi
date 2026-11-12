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
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code Registration} type that extends {@code TypeLiteral} matches a bean type that holds an array among its
 * type arguments, compared dimension by dimension (CDI 5.0, section 12.4).
 */
class RegistrationArrayTypeLiteralTest {

    static final List<String> DESCRIBED = new ArrayList<>();

    private static final String SOURCE = """
        package arrays;

        @jakarta.enterprise.context.Dependent
        public class Producers {

            @jakarta.enterprise.inject.Produces
            java.util.List<String[]> strings() {
                return java.util.List.of();
            }

            @jakarta.enterprise.inject.Produces
            java.util.List<String[][]> deeper() {
                return java.util.List.of();
            }

            @jakarta.enterprise.inject.Produces
            java.util.List<java.util.List<Integer>[]> generic() {
                return java.util.List.of();
            }
        }
        """;

    @Test
    void anArrayTypeArgumentMatches() {
        DESCRIBED.clear();
        try (JavaParser parser = ExtensionCompilation.of(Arrays.class)) {
            parser.generate("arrays.Producers", SOURCE);
        }
        assertEquals(List.of("strings", "generic"), DESCRIBED);
    }

    /**
     * The registration methods.
     */
    public static final class Arrays implements BuildCompatibleExtension {
        @Registration(types = ListOfStringArray.class)
        public void strings(BeanInfo bean) {
            DESCRIBED.add(bean.producerMethod().name());
        }

        @Registration(types = ListOfListArray.class)
        public void generic(BeanInfo bean) {
            DESCRIBED.add(bean.producerMethod().name());
        }
    }

    /**
     * A list of arrays of String.
     */
    public static final class ListOfStringArray extends TypeLiteral<List<String[]>> {
    }

    /**
     * A list of arrays of a list of Integer, a generic array.
     */
    public static final class ListOfListArray extends TypeLiteral<List<List<Integer>[]>> {
    }
}
