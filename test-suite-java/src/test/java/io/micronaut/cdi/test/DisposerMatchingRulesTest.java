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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A disposer disposes of what a producer of its class produces by the rules of typesafe resolution (sections 2.4.2
 * and 3.4.3): one that matches none of them is the definition error of section 3.3.7, which is how a disposer
 * that must not match its producer is told here.
 */
class DisposerMatchingRulesTest {

    private static final String NO_PRODUCER = "No producer of this class produces what this disposer method disposes of";

    private static String maker(String producer, String disposer) {
        return """
            package broken;

            import jakarta.enterprise.inject.Disposes;
            import jakarta.enterprise.inject.Produces;
            import java.util.List;

            @jakarta.enterprise.context.Dependent
            public class Subject {

                public static class Box<T> {
                }

                @jakarta.inject.Qualifier
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Repeatable(Starts.class)
                public @interface Start {
                    String value();
                }

                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Starts {
                    Start[] value();
                }

                %s

                %s
            }
            """.formatted(producer, disposer);
    }

    private static void assertMatches(String producer, String disposer) {
        try (JavaParser parser = new JavaParser()) {
            assertDoesNotThrow(() -> parser.generate("broken.Subject", maker(producer, disposer)));
        }
    }

    @Test
    void anActualTypeArgumentOutsideTheBoundOfTheProducedVariableDoesNotMatch() {
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@Produces <T extends Number> Box<T> make() { return new Box<>(); }",
            "void dispose(@Disposes Box<String> box) { }"), NO_PRODUCER);
        assertMatches(
            "@Produces <T extends Number> Box<T> make() { return new Box<>(); }",
            "void dispose(@Disposes Box<Integer> box) { }");
    }

    @Test
    void aWildcardBoundIsComparedWithItsTypeArguments() {
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@Produces Box<List<Integer>> make() { return new Box<>(); }",
            "void dispose(@Disposes Box<? extends List<String>> box) { }"), NO_PRODUCER);
        assertMatches(
            "@Produces Box<List<Integer>> make() { return new Box<>(); }",
            "void dispose(@Disposes Box<? extends List<Integer>> box) { }");
    }

    @Test
    void aRawProducedTypeMatchesOnlyAParameterizationThatAsksNothingOfItsArguments() {
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@SuppressWarnings(\"rawtypes\") @Produces Box make() { return new Box(); }",
            "void dispose(@Disposes Box<String> box) { }"), NO_PRODUCER);
        assertMatches(
            "@SuppressWarnings(\"rawtypes\") @Produces Box make() { return new Box(); }",
            "void dispose(@Disposes Box<Object> box) { }");
    }

    @Test
    void anArrayProducerIsDisposedOfAsItsOwnArrayTypeOrObject() {
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@Produces String[] make() { return new String[0]; }",
            "void dispose(@Disposes Object[] array) { }"), NO_PRODUCER);
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@Produces String[][] make() { return new String[0][]; }",
            "void dispose(@Disposes Object[] array) { }"), NO_PRODUCER);
        assertMatches(
            "@Produces String[] make() { return new String[0]; }",
            "void dispose(@Disposes String[] array) { }");
        assertMatches(
            "@Produces String[] make() { return new String[0]; }",
            "void dispose(@Disposes Object array) { }");
    }

    @Test
    void everyRepetitionOfARepeatableQualifierIsRequired() {
        InjectionDefinitionErrorTest.assertRefused(maker(
            "@Produces @Start(\"a\") @Start(\"b\") String make() { return \"\"; }",
            "void dispose(@Disposes @Start(\"a\") @Start(\"c\") String made) { }"), NO_PRODUCER);
        assertMatches(
            "@Produces @Start(\"a\") @Start(\"b\") String make() { return \"\"; }",
            "void dispose(@Disposes @Start(\"b\") @Start(\"a\") String made) { }");
    }
}
