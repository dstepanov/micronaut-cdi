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

import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two compilations of one application - two modules, or the main and the test classes of one - each run the
 * build compatible extensions, and each generates classes for what they said. None of those may be named as a
 * class the other generated: the class loader would hand out one of them, and what the other recorded would be
 * lost. The classes of this suite were compiled with the extensions of the suite; a second compilation with the
 * same extensions generates its own.
 */
class GeneratedClassNamesTest {

    private static final String GENERATED = "io.micronaut.cdi.generated.";

    @Test
    void aSecondCompilationGeneratesNoClassTheFirstGenerated() {
        String source = """
            package collide;

            @jakarta.enterprise.context.Dependent
            public class Subject {
            }
            """;
        List<String> generated = new ArrayList<>();
        try (JavaParser parser = new JavaParser()) {
            for (JavaFileObject file : parser.generate("collide.Subject", source)) {
                if (file.getKind() == JavaFileObject.Kind.CLASS) {
                    String name = file.getName();
                    name = name.substring(name.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length(),
                        name.length() - ".class".length()).replace('/', '.');
                    if (name.startsWith(GENERATED)) {
                        generated.add(name);
                    }
                }
            }
        }
        assertFalse(generated.isEmpty(), "the extensions of the suite generate classes in any compilation");
        ClassLoader suite = GeneratedClassNamesTest.class.getClassLoader();
        List<String> taken = new ArrayList<>();
        for (String name : generated) {
            if (suite.getResource(name.replace('.', '/') + ".class") != null) {
                taken.add(name);
            }
        }
        assertTrue(taken.isEmpty(), "generated again under the name of a class of this suite: " + taken);
    }
}
