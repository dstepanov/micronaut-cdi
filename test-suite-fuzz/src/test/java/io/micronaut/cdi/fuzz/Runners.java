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

import io.micronaut.cdi.fuzz.RunResult.Outcome;
import io.micronaut.cdi.fuzz.ScenarioRenderer.Rendered;
import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import jakarta.enterprise.inject.se.SeContainer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a rendered scenario on micronaut-cdi and on Weld.
 *
 * <p>micronaut-cdi runs in a class loader over the scenario's compiled classes whose parent is the test class path,
 * which holds the container. Weld runs in a class loader of its own over the Weld class path, shared by every run,
 * with a child per scenario: nothing of the test class path is visible to it, so the CDI providers of the two never
 * meet. Each side talks to its own copy of the scenario's classes through the {@code SeContainer} API alone.</p>
 */
final class Runners {

    private static final long TIMEOUT_SECONDS = 60;
    private static volatile URLClassLoader weldLoader;

    static {
        // Weld logs through JBoss Logging, which falls back to the JDK's logging here; its banner and its warnings
        // about the deployments it is handed are noise in a campaign's output
        Logger.getLogger("org.jboss.weld").setLevel(Level.SEVERE);
        Logger.getLogger("").setLevel(Level.SEVERE);
        System.setProperty("org.jboss.logging.provider", "jdk");
    }

    private Runners() {
    }

    static RunResult runMicronaut(Rendered rendered, Path workDir) {
        Path output = workDir.resolve("micronaut-classes");
        ScenarioCompiler.Result compiled = ScenarioCompiler.compileForMicronaut(rendered.sources(), output);
        if (!compiled.success()) {
            return refusedByCompiler(compiled);
        }
        return withTimeout(() -> {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()},
                Runners.class.getClassLoader())) {
                return inContext(loader, () -> {
                    SeContainer container;
                    try {
                        container = new MicronautSeContainerInitializer().setClassLoader(loader).initialize();
                    } catch (Throwable t) {
                        return RunResult.refused(t);
                    }
                    try {
                        Method run = loader.loadClass(rendered.packageName() + ".Driver").getMethod("run", SeContainer.class);
                        return RunResult.deployed(traceOf(run.invoke(null, container)));
                    } catch (InvocationTargetException e) {
                        return RunResult.of(Outcome.CRASH, false, RunResult.stackTrace(e.getCause()));
                    } finally {
                        closeQuietly(container);
                    }
                });
            }
        });
    }

    /**
     * What a compilation for micronaut-cdi that failed says: a refusal of the scenario where the processor reported a
     * finding, and no verdict at all where a processor crashed, which is a bug of the harness or of the processor
     * rather than a definition error to compare with what Weld says.
     */
    static RunResult refusedByCompiler(ScenarioCompiler.Result compiled) {
        return compiled.crashed() ? RunResult.of(Outcome.HARNESS_ERROR, true, String.join("\n", compiled.errors()))
            : RunResult.refusedByCompiler(compiled.errors());
    }

    static RunResult runWeld(Rendered rendered, Path workDir) {
        Path output = workDir.resolve("weld-classes");
        ScenarioCompiler.Result compiled = ScenarioCompiler.compileForWeld(rendered.sources(), output);
        if (!compiled.success()) {
            return RunResult.of(Outcome.HARNESS_ERROR, true, String.join("\n", compiled.errors()));
        }
        return withTimeout(() -> {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, weldLoader())) {
                return inContext(loader, () -> {
                    Class<?> weldType = loader.loadClass("org.jboss.weld.environment.se.Weld");
                    Object weld = weldType.getConstructor(String.class).newInstance("fuzz-" + System.nanoTime());
                    weldType.getMethod("disableDiscovery").invoke(weld);
                    List<Class<?>> beanClasses = new ArrayList<>();
                    for (String name : rendered.beanClasses()) {
                        beanClasses.add(loader.loadClass(name));
                    }
                    weldType.getMethod("addBeanClasses", Class[].class).invoke(weld, (Object) beanClasses.toArray(Class[]::new));
                    weldType.getMethod("property", String.class, Object.class)
                        .invoke(weld, "org.jboss.weld.se.shutdownHook", false);
                    weldType.getMethod("property", String.class, Object.class)
                        .invoke(weld, "org.jboss.weld.bootstrap.concurrentDeployment", false);
                    weldType.getMethod("setClassLoader", ClassLoader.class).invoke(weld, loader);
                    Object container;
                    try {
                        container = weldType.getMethod("initialize").invoke(weld);
                    } catch (InvocationTargetException e) {
                        return RunResult.refused(e.getCause());
                    }
                    Class<?> seContainer = loader.loadClass("jakarta.enterprise.inject.se.SeContainer");
                    try {
                        Method run = loader.loadClass(rendered.packageName() + ".Driver").getMethod("run", seContainer);
                        return RunResult.deployed(traceOf(run.invoke(null, container)));
                    } catch (InvocationTargetException e) {
                        return RunResult.of(Outcome.CRASH, false, RunResult.stackTrace(e.getCause()));
                    } finally {
                        try {
                            if ((boolean) seContainer.getMethod("isRunning").invoke(container)) {
                                seContainer.getMethod("close").invoke(container);
                            }
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            // what the driver already closed, or could not
                        }
                    }
                });
            }
        });
    }

    private static List<String> traceOf(Object value) {
        List<String> trace = new ArrayList<>();
        for (Object line : (List<?>) value) {
            trace.add(String.valueOf(line));
        }
        return trace;
    }

    private static void closeQuietly(SeContainer container) {
        try {
            if (container.isRunning()) {
                container.close();
            }
        } catch (RuntimeException e) {
            // what the driver already closed, or could not
        }
    }

    private static URLClassLoader weldLoader() {
        URLClassLoader loader = weldLoader;
        if (loader == null) {
            synchronized (Runners.class) {
                loader = weldLoader;
                if (loader == null) {
                    List<URL> urls = new ArrayList<>();
                    for (String entry : FuzzConfig.weldPath().split(File.pathSeparator)) {
                        if (entry.isBlank()) {
                            continue;
                        }
                        try {
                            urls.add(new File(entry).toURI().toURL());
                        } catch (MalformedURLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    loader = new URLClassLoader("weld", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
                    weldLoader = loader;
                }
            }
        }
        return loader;
    }

    private static <T> T inContext(ClassLoader loader, Callable<T> work) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return work.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Runs one side on a thread of its own, so that a scenario that hangs a container costs a minute rather than
     * the campaign. The thread of a run that timed out is abandoned.
     */
    private static RunResult withTimeout(Callable<RunResult> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "fuzz-run");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<RunResult> future = executor.submit(work);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return RunResult.of(Outcome.TIMEOUT, false, "no result after " + TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            return RunResult.of(Outcome.CRASH, false, RunResult.stackTrace(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
