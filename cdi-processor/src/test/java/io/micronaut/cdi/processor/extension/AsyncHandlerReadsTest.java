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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The async handlers of the method invokers are read from the class path once per compilation, however many
 * invokers it builds: each read of the class path is a scan Core announces with a compiler note.
 */
class AsyncHandlerReadsTest {

    private static final String SOURCE = """
        package invokers;

        @jakarta.enterprise.context.Dependent
        public class Service {
            public String callA() {
                return "a";
            }

            public String callB() {
                return "b";
            }

            public String callC() {
                return "c";
            }
        }
        """;

    private static final String SCANNING = "EXPERIMENTAL: Compile time resource scanning";

    @Test
    void theClassPathIsReadOncePerCompilationWhateverTheNumberOfInvokers() {
        Compiled without = compile(null);
        Compiled with = compile(Invoking.class.getName());
        assertEquals(0, without.built());
        assertEquals(3, with.built(), "the extension built an invoker for each method");
        // the handlers are read as the compilation starts, and not again for each invoker built
        assertEquals(without.scans(), with.scans(), "the class path was scanned " + with.scans() + " times");
        // one read of each of the two service entries of the async handlers, and no probe beside them
        assertTrue(with.scans() <= 2, "the class path was scanned " + with.scans() + " times");
    }

    private static Compiled compile(String extension) {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                if (extension != null) {
                    options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + extension);
                }
                options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
                return options;
            }
        }) {
            parser.generate("invokers.Service", SOURCE);
            int built = 0;
            int scans = 0;
            for (Diagnostic<? extends JavaFileObject> diagnostic : parser.getDiagnosticCollector().getDiagnostics()) {
                String message = diagnostic.getMessage(null);
                if (message.startsWith("Built invoker ")) {
                    built++;
                }
                if (message.contains(SCANNING)) {
                    scans++;
                }
            }
            return new Compiled(built, scans);
        }
    }

    private record Compiled(int built, int scans) {
    }

    /**
     * An extension, named by the options, that builds an invoker for each method of the service.
     */
    public static final class Invoking implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers, Messages messages) {
            if (!bean.declaringClass().name().equals("invokers.Service")) {
                return;
            }
            for (MethodInfo method : bean.declaringClass().methods()) {
                if (method.name().startsWith("call")) {
                    invokers.createInvoker(bean, method).build();
                    messages.info("Built invoker " + method.name());
                }
            }
        }
    }
}
