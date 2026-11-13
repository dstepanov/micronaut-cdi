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
import jakarta.enterprise.invoke.AsyncHandler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two async handlers of one async type are a deployment problem unless one is selected (CDI 5.0, section 10.5),
 * whatever their kinds and whether one is built in.
 */
class AsyncHandlerSelectionTest {

    private static final String SOURCE = """
        package selected;

        @jakarta.enterprise.context.Dependent
        public class Target {
        }
        """;

    @Test
    void aReturnTypeAndAParameterTypeHandlerOfOneTypeCollide() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> compile(names(ReturnHandler.class, ParameterHandler.class), null));
        assertTrue(failure.getMessage().contains("both handle the async type"), failure.getMessage());
    }

    @Test
    void aSelectedHandlerLeavesTheOthersOut() {
        assertDoesNotThrow(() -> compile(names(ReturnHandler.class, ParameterHandler.class),
            Async.class.getName() + "=" + ReturnHandler.class.getName()));
    }

    @Test
    void aCustomHandlerOfABuiltInTypeCollidesWithTheBuiltInOne() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> compile(names(StageHandler.class), null));
        assertTrue(failure.getMessage().contains("built into the container"), failure.getMessage());
        assertDoesNotThrow(() -> compile(names(StageHandler.class),
            CompletionStage.class.getName() + "=" + StageHandler.class.getName()));
    }

    @Test
    void theBuiltInHandlerMayBeSelectedOverARegisteredOne() {
        assertDoesNotThrow(() -> compile(names(StageHandler.class), CompletionStage.class.getName() + "=builtin"));
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> compile(names(ReturnHandler.class), Async.class.getName() + "=builtin"));
        assertTrue(failure.getMessage().contains("no built-in async handler"), failure.getMessage());
    }

    @Test
    void aSelectionOfAHandlerOfAnotherTypeIsADeploymentProblem() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> compile(names(ReturnHandler.class), CompletionStage.class.getName() + "="
                + ReturnHandler.class.getName()));
        assertTrue(failure.getMessage().contains("handles " + Async.class.getName().replace('$', '.')),
            failure.getMessage());
    }

    private static String names(Class<?>... handlers) {
        StringBuilder names = new StringBuilder();
        for (Class<?> handler : handlers) {
            names.append(names.isEmpty() ? "" : ",").append(handler.getName());
        }
        return names.toString();
    }

    private static void compile(String handlers, String selection) {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                options.add("-Amicronaut.cdi.asyncHandlers=" + handlers);
                if (selection != null) {
                    options.add("-Amicronaut.cdi.asyncHandlerSelection=" + selection);
                }
                return options;
            }
        }) {
            parser.generate("selected.Target", SOURCE);
        }
    }

    /**
     * The async type both handlers handle.
     */
    public interface Async {
    }

    /**
     * A return type handler of {@link Async}.
     */
    public static final class ReturnHandler implements AsyncHandler.ReturnType<Async> {
        @Override
        public Async transform(Async original, Runnable completion) {
            return original;
        }
    }

    /**
     * A parameter type handler of {@link Async}.
     */
    public static final class ParameterHandler implements AsyncHandler.ParameterType<Async> {
        @Override
        public Async transformArgument(Async original, Runnable completion) {
            return original;
        }
    }

    /**
     * A return type handler of a type the container has a built-in handler of.
     */
    public static final class StageHandler implements AsyncHandler.ReturnType<CompletionStage<?>> {
        @Override
        public CompletionStage<?> transform(CompletionStage<?> original, Runnable completion) {
            return original;
        }
    }
}
