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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An async handler is registered by a service entry on the class path of the compilation, where an application's
 * handler library is, not only on the processor path (CDI 5.0, section 10.5).
 */
class AsyncHandlerClasspathTest {

    private static final String SOURCE = """
        package classpath;

        @jakarta.enterprise.context.Dependent
        public class Target {
        }
        """;

    @TempDir
    Path library;

    @Test
    void aHandlerRegisteredOnTheClassPathOfTheCompilationIsAHandler() throws IOException {
        Path services = library.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("jakarta.enterprise.invoke.AsyncHandler$ReturnType"),
            ClasspathHandler.class.getName() + "\n");
        // the selection names the handler, which is a deployment problem where it is no registered handler
        assertDoesNotThrow(() -> compile(library, Async.class.getName() + "=" + ClasspathHandler.class.getName()));
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> compile(null, Async.class.getName() + "=" + ClasspathHandler.class.getName()));
        assertTrue(failure.getMessage().contains("is not a registered async handler"), failure.getMessage());
    }

    @Test
    void aNestedHandlerNamedTwiceIsOneHandler() throws IOException {
        Path services = library.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("jakarta.enterprise.invoke.AsyncHandler$ReturnType"),
            ClasspathHandler.class.getName() + "\n");
        assertDoesNotThrow(() -> compile(library, null,
            ClasspathHandler.class.getName().replace('$', '.')));
    }

    private static void compile(Path library, String selection, String... handlers) {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                if (library != null) {
                    options.add("-classpath");
                    options.add(library + File.pathSeparator + System.getProperty("java.class.path"));
                }
                if (selection != null) {
                    options.add("-Amicronaut.cdi.asyncHandlerSelection=" + selection);
                }
                if (handlers.length > 0) {
                    options.add("-Amicronaut.cdi.asyncHandlers=" + String.join(",", handlers));
                }
                return options;
            }
        }) {
            parser.generate("classpath.Target", SOURCE);
        }
    }

    /**
     * The async type of the handler.
     */
    public interface Async {
    }

    /**
     * A return type handler a library registers.
     */
    public static final class ClasspathHandler implements AsyncHandler.ReturnType<Async> {
        @Override
        public Async transform(Async original, Runnable completion) {
            return original;
        }
    }
}
