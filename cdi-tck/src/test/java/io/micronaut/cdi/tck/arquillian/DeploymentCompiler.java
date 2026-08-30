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

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Compiles the classes of one deployment, the way the whole classpath was compiled: deployment is compilation.
 *
 * <p>The deployments the kit expects a container to reject are not compiled with the module — each is compiled
 * here, alone, when its test deploys it. What the compiler rejects is reported the way the specification names
 * it: a problem with a definition is a {@code DefinitionException}, and a bean that cannot live in its normal
 * scope because it cannot be proxied is what section 2.2.10 has a deployment problem, a
 * {@code DeploymentException}. The distinction is read off the compiler's diagnostics rather than parsed out of
 * an exception message.</p>
 */
final class DeploymentCompiler {

    private DeploymentCompiler() {
    }

    /**
     * Compiles the given sources with the same processors the module compiles with, a fresh compiler per
     * deployment because the compiler's file manager accumulates state.
     *
     * @param sourcesByClassName The source of each class
     * @return The compiler's output
     */
    static Iterable<? extends JavaFileObject> compile(Map<String, String> sourcesByClassName) {
        JavaFileObject[] sources = sourcesByClassName.entrySet().stream()
            .map(entry -> sourceFile(entry.getKey(), entry.getValue()))
            .toArray(JavaFileObject[]::new);
        try (JavaParser parser = new JavaParser()) {
            try {
                return parser.generate(sources);
            } catch (RuntimeException e) {
                throw classified(parser.getDiagnosticCollector().getDiagnostics(), e);
            }
        }
    }

    private static JavaFileObject sourceFile(String className, String source) {
        URI uri = URI.create("string:///" + className.replace('.', '/') + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static RuntimeException classified(List<Diagnostic<? extends JavaFileObject>> diagnostics,
                                               RuntimeException failure) {
        StringBuilder report = new StringBuilder("The deployment does not compile:");
        boolean unproxyable = false;
        boolean anyError = false;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            anyError = true;
            String message = diagnostic.getMessage(null);
            report.append('\n').append(message);
            // the proxy writer's own diagnostics: a bean that cannot be proxied cannot live in its normal
            // scope, which the specification calls a problem with the deployment rather than the definition
            if (message.contains("Cannot apply AOP advice")
                || message.contains("inherits AOP advice but is declared final")
                || message.contains("cannot be proxied")
                // an extension that reported a deployment problem itself: the exception's type survives only
                // in the diagnostic's text once the compiler has reported it
                || message.contains("jakarta.enterprise.inject.spi.DeploymentException")) {
                unproxyable = true;
            }
        }
        if (!anyError) {
            // the compiler failed without an error diagnostic, which is a harness problem rather than a finding
            return failure;
        }
        return unproxyable
            ? new DeploymentException(report.toString(), failure)
            : new DefinitionException(report.toString(), failure);
    }
}
