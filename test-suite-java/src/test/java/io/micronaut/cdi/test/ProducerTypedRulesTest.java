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

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code Typed} on a producer method or field that names a type which is not a bean type of the product is a
 * definition error (CDI 4.1 section 2.2.2), as it is on a bean class.
 */
class ProducerTypedRulesTest {

    @Test
    void aProducerMethodTypedToATypeItDoesNotHaveIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.inject.Typed(Integer.class)
                String produce() {
                    return "";
                }
            }
            """);
    }

    @Test
    void aProducerFieldTypedToATypeItDoesNotHaveIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.inject.Typed(Runnable.class)
                StringBuilder produced = new StringBuilder();
            }
            """);
    }

    @Test
    void anArrayProducerTypedToAnotherArrayTypeIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.inject.Typed(Integer[].class)
                String[] produce() {
                    return new String[0];
                }
            }
            """);
    }

    @Test
    void aProducerTypedToTypesItHasCompiles() {
        try (JavaParser parser = new JavaParser()) {
            assertDoesNotThrow(() -> parser.generate("valid.Subject", """
                package valid;

                @jakarta.enterprise.context.Dependent
                public class Subject {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed({CharSequence.class, Object.class})
                    String produce() {
                        return "";
                    }

                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed
                    StringBuilder produced = new StringBuilder();

                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed(int[][].class)
                    int[][] matrix() {
                        return new int[0][];
                    }
                }
                """));
        }
    }

    private static void assertRefused(String source) {
        try (JavaParser parser = new JavaParser()) {
            assertThrows(RuntimeException.class, () -> parser.generate("broken.Subject", source),
                "the producer is refused as it compiles");
            String errors = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
            assertTrue(errors.contains("which is not one of its types"), "expected a definition error, got: " + errors);
        }
    }
}
