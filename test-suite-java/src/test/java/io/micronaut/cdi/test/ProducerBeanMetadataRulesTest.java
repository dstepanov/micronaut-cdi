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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code @Default Bean} a producer or disposer method parameter is handed is the metadata of the bean produced or
 * disposed of, so its type parameter must be the producer's return type or the disposed parameter's type, and a
 * {@code Bean} of the class declaring the method is a definition error there (CDI 4.1 section 5.5.8), as the runtime
 * could not hand it anything else.
 */
class ProducerBeanMetadataRulesTest {

    @Test
    void theBeanOfTheDeclaringClassAtAProducerParameterIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                StringBuilder produce(jakarta.enterprise.inject.spi.Bean<Subject> bean) {
                    return new StringBuilder();
                }
            }
            """);
    }

    @Test
    void theBeanOfTheDeclaringClassAtADisposerParameterIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                StringBuilder produce() {
                    return new StringBuilder();
                }

                void dispose(@jakarta.enterprise.inject.Disposes StringBuilder produced,
                             jakarta.enterprise.inject.spi.Bean<Subject> bean) {
                }
            }
            """);
    }

    private static void assertRefused(String source) {
        try (JavaParser parser = new JavaParser()) {
            assertThrows(RuntimeException.class, () -> parser.generate("broken.Subject", source),
                "the method is refused as it compiles");
            String errors = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
            assertTrue(errors.contains("is not the metadata of"), "expected a definition error, got: " + errors);
        }
    }
}
