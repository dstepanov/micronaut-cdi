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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The compilation of a deployment that names its extensions through the processor's options leaves the system
 * properties as it found them, a value set before it included: Micronaut's processor restores what it copied there,
 * and the harness restores the same rather than clearing it.
 */
class DeploymentCompilerOptionsTest {

    public static class Quiet implements BuildCompatibleExtension {
    }

    @Test
    void aValueSetBeforeTheCompilationIsThereAfterIt() {
        String only = BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION;
        String extensions = BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION;
        String previousOnly = System.getProperty(only);
        String previousExtensions = System.getProperty(extensions);
        System.setProperty(only, "false");
        System.clearProperty(extensions);
        try {
            DeploymentCompiler.compile(Map.of("tck.options.Subject", """
                package tck.options;

                @jakarta.enterprise.context.Dependent
                public class Subject {
                }
                """), List.of(Quiet.class.getName()));
            assertEquals("false", System.getProperty(only), "the value set before the compilation is restored");
            assertNull(System.getProperty(extensions), "a property absent before the compilation is absent after it");
        } finally {
            restore(only, previousOnly);
            restore(extensions, previousExtensions);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
