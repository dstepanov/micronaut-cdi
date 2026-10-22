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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The type closure the processor records and describes (section 2.2.1) walks a hierarchy once: a type reached along
 * two paths of a diamond is one type of the closure, visited once, and the supertypes of a raw supertype are raw.
 */
class TypeClosureWalkTest {

    private static final String PREFIX = "closure of ";

    @Test
    void aTypeReachedAlongTwoPathsOfADiamondIsVisitedOnce() {
        List<String> closure = closureOf("diamond.Bottom", """
            package diamond;

            interface Top<T> {
            }

            interface Left<T> extends Top<T> {
            }

            interface Right<T> extends Top<T> {
            }

            class Base implements Top<String> {
            }

            @jakarta.enterprise.context.Dependent
            public class Bottom extends Base implements Left<String>, Right<String> {
            }
            """);
        assertEquals(List.of("diamond.Bottom", "diamond.Base", "diamond.Top<java.lang.String>",
            "diamond.Left<java.lang.String>", "diamond.Right<java.lang.String>"), closure);
    }

    @Test
    void theSupertypesOfARawSuperclassAreRaw() {
        List<String> closure = closureOf("diamond.RawChild", """
            package diamond;

            interface Tray<T> {
            }

            class Bin<T> implements Tray<T> {
            }

            @SuppressWarnings("rawtypes")
            @jakarta.enterprise.context.Dependent
            public class RawChild extends Bin {
            }
            """);
        assertEquals(List.of("diamond.RawChild", "raw diamond.Bin", "raw diamond.Tray"), closure);
    }

    private static List<String> closureOf(String className, String source) {
        try (JavaParser parser = new JavaParser()) {
            parser.generate(className, source);
            List<String> closure = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : parser.getDiagnosticCollector().getDiagnostics()) {
                String message = diagnostic.getMessage(null);
                if (message.startsWith(PREFIX + className + ": ")) {
                    closure.add(message.substring((PREFIX + className + ": ").length()));
                }
            }
            return closure;
        }
    }

    /**
     * Reports each type of the closure of a class of the test package as the walk visits it, registered as a visitor
     * in the test resources of this module.
     */
    public static final class ClosureReporter implements TypeElementVisitor<Object, Object> {

        @Override
        public VisitorKind getVisitorKind() {
            return VisitorKind.ISOLATING;
        }

        @Override
        public void visitClass(ClassElement element, VisitorContext context) {
            if (!"diamond".equals(element.getPackageName())) {
                return;
            }
            for (ClassElement type : BeanTypesVisitor.closureOf(element)) {
                boolean raw = type.isRawType() && !type.getDeclaredGenericPlaceholders().isEmpty();
                StringBuilder described = new StringBuilder(raw ? "raw " : "").append(type.getName());
                if (!raw && !type.getTypeArguments().isEmpty()) {
                    described.append('<');
                    type.getTypeArguments().values().forEach(argument -> described.append(argument.getName()));
                    described.append('>');
                }
                context.info(PREFIX + element.getName() + ": " + described);
            }
        }
    }
}
