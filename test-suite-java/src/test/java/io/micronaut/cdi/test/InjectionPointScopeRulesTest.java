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
 * The {@code @Default InjectionPoint} may be injected only into a dependent bean: a bean that declares any other scope
 * - a pseudo-scope of the application's own as well as a normal scope - is a definition error (CDI 4.1 section
 * 6.5.3). A class written with {@code jakarta.inject.Singleton} alone is exempt from the processor's rules, as
 * CONFORMANCE.md records, since Micronaut's own beans carry the same annotation.
 */
class InjectionPointScopeRulesTest {

    @Test
    void anInjectionPointOfAProducerOfAPseudoScopeOtherThanDependentIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                @jakarta.inject.Singleton
                StringBuilder produce(jakarta.enterprise.inject.spi.InjectionPoint point) {
                    return new StringBuilder();
                }
            }
            """);
    }

    @Test
    void anInjectionPointOfAProducerOfAPseudoScopeOfTheApplicationIsRefused() {
        assertRefused("""
            package broken;

            @jakarta.enterprise.context.Dependent
            public class Subject {
                @jakarta.enterprise.inject.Produces
                @Lasting
                StringBuilder produce(jakarta.enterprise.inject.spi.InjectionPoint point) {
                    return new StringBuilder();
                }

                @jakarta.inject.Scope
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Lasting {
                }
            }
            """);
    }

    private static void assertRefused(String source) {
        try (JavaParser parser = new JavaParser()) {
            assertThrows(RuntimeException.class, () -> parser.generate("broken.Subject", source),
                "the producer is refused as it compiles");
            String errors = parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
            assertTrue(errors.contains("section 6.5.3"), "expected a definition error, got: " + errors);
        }
    }

    @Test
    void anInjectionPointInAClassWrittenWithSingletonAloneIsExempt() {
        try (JavaParser parser = new JavaParser()) {
            assertDoesNotThrow(() -> parser.generate("exempt.Subject", """
                package exempt;

                @jakarta.inject.Singleton
                public class Subject {
                    @jakarta.inject.Inject
                    jakarta.enterprise.inject.spi.InjectionPoint point;
                }
                """));
        }
    }
}
