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
package io.micronaut.cdi.fuzz;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles the sources of a scenario to a directory, the way a build compiles an application: with this module's
 * annotation processors for micronaut-cdi, with none for Weld.
 */
final class ScenarioCompiler {

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    private ScenarioCompiler() {
    }

    /**
     * What a compilation produced.
     *
     * @param success Whether it compiled
     * @param errors  The error diagnostics, as the compiler worded them
     */
    record Result(boolean success, List<String> errors) {
    }

    /**
     * Compiles for micronaut-cdi: the test classpath, which holds the container, and the processors.
     */
    static Result compileForMicronaut(Map<String, String> sources, Path output) {
        return compile(sources, output, List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-processorpath", FuzzConfig.processorPath(),
            "-proc:full",
            "-parameters"), FuzzConfig.processorPath());
    }

    /**
     * Compiles for Weld: the API alone, from the Weld class path, and no processing.
     */
    static Result compileForWeld(Map<String, String> sources, Path output) {
        return compile(sources, output, List.of(
            "-classpath", FuzzConfig.weldPath(),
            "-proc:none",
            "-parameters"), null);
    }

    private static Result compile(Map<String, String> sources, Path output, List<String> baseOptions,
                                  String processorPath) {
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> options = new ArrayList<>(baseOptions);
        options.add("-d");
        options.add(output.toString());
        options.add("-Xlint:none");
        options.add("-nowarn");
        List<JavaFileObject> files = new ArrayList<>();
        sources.forEach((name, source) -> files.add(new SimpleJavaFileObject(
            URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        }));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter compilerOutput = new StringWriter();
        boolean success;
        try (StandardJavaFileManager standard = JAVAC.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             ProcessorLoader processors = processorPath == null ? null : ProcessorLoader.of(processorPath)) {
            JavaFileManager fileManager = processors == null ? standard : new ForwardingJavaFileManager<>(standard) {
                @Override
                public ClassLoader getClassLoader(Location location) {
                    return location == StandardLocation.ANNOTATION_PROCESSOR_PATH ? processors
                        : super.getClassLoader(location);
                }
            };
            try {
                success = JAVAC.getTask(compilerOutput, fileManager, diagnostics, options, null, files).call();
            } catch (RuntimeException e) {
                // a processor that threw rather than reported: javac wraps it
                List<String> errors = new ArrayList<>();
                errors.add("the compiler threw " + e);
                for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                    errors.add("caused by " + cause);
                }
                return new Result(false, errors);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String where = diagnostic.getSource() == null ? "" : diagnostic.getSource().getName()
                    .replace("string:///", "") + ":" + diagnostic.getLineNumber() + ": ";
                errors.add(where + diagnostic.getMessage(null));
            }
        }
        if (!success && errors.isEmpty()) {
            errors.add(compilerOutput.toString());
        }
        return new Result(success, errors);
    }

    /**
     * The class loader of the processors. javac would make the test class loader its parent, and the test class path
     * holds micronaut-inject too, so the processors' copy and the test's would split its packages. This loader sees the
     * platform classes and the compiler's own API, and nothing else of the test class path.
     */
    private static final class ProcessorLoader extends URLClassLoader {

        private static final ClassLoader COMPILER = ScenarioCompiler.class.getClassLoader();

        private ProcessorLoader(URL[] urls) {
            super("fuzz-processors", urls, ClassLoader.getPlatformClassLoader());
        }

        static ProcessorLoader of(String path) {
            List<URL> urls = new ArrayList<>();
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    try {
                        urls.add(new File(entry).toURI().toURL());
                    } catch (MalformedURLException e) {
                        throw new IllegalArgumentException(entry, e);
                    }
                }
            }
            return new ProcessorLoader(urls.toArray(URL[]::new));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.sun.source.") || name.startsWith("com.sun.tools.javac.")) {
                return COMPILER.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
