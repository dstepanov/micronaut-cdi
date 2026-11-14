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
package io.micronaut.cdi.processor.extension;

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.BuildServicesResolver;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The build services the specification's API keeps in a static of its own hold no compilation once it is over:
 * the class loader of the processor may outlive the compilation, in a build daemon or a compiler worker.
 */
class BuildServicesReleaseTest {

    private static final String SOURCE = """
        package released;

        @jakarta.enterprise.context.Dependent
        public class Subject {
        }
        """;

    @Test
    void aFinishedCompilationLeavesNoContextInTheBuildServices() throws ReflectiveOperationException {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + Discovering.class.getName());
                options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
                return options;
            }
        }) {
            parser.generate("released.Subject", SOURCE);
        }
        // the static of the API, read the only way a test can
        Field configured = BuildServicesResolver.class.getDeclaredField("configuredBuildServices");
        configured.setAccessible(true);
        Object services = ((AtomicReference<?>) configured.get(null)).get();
        ElementBuildServices element = assertInstanceOf(ElementBuildServices.class, services);
        Field context = ElementBuildServices.class.getDeclaredField("context");
        context.setAccessible(true);
        assertNull(context.get(element), "the compilation's context is still held");
    }

    /**
     * An extension, named by the options, with a phase method of its own.
     */
    public static final class Discovering implements BuildCompatibleExtension {
        @Discovery
        public void discover() {
        }
    }
}
