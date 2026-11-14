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
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a compilation with build compatible extensions writes for the whole deployment is named after one of its
 * classes, and the same one whichever order the compiler visits them in: a build that processes the classes in
 * another order without cleaning would otherwise leave the definitions under the old name beside the new ones.
 */
class ContributorOrderTest {

    private static final String FIRST = """
        package order;

        @jakarta.enterprise.context.Dependent
        public class Alpha {
        }
        """;

    private static final String SECOND = """
        package order;

        @jakarta.enterprise.context.Dependent
        public class Beta {
        }
        """;

    @Test
    void theNamesDoNotDependOnTheOrderTheClassesAreVisitedIn() {
        Set<String> alphaFirst = generated(source("order.Alpha", FIRST), source("order.Beta", SECOND));
        Set<String> betaFirst = generated(source("order.Beta", SECOND), source("order.Alpha", FIRST));
        assertEquals(alphaFirst, betaFirst);
    }

    private static Set<String> generated(JavaFileObject... sources) {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + Discovering.class.getName());
                options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
                return options;
            }
        }) {
            Set<String> names = new TreeSet<>();
            for (JavaFileObject file : parser.generate(sources)) {
                names.add(file.getName());
            }
            return names;
        }
    }

    private static JavaFileObject source(String className, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
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
