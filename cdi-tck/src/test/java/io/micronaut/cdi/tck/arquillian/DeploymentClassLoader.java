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

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loader of a deployment that was compiled as it deployed.
 *
 * <p>It is parent-first, which is what keeps a scenario class the same class the kit's test asserts with: the
 * kit ships compiled copies of every scenario, the parent loads them, and only what the parent does not have —
 * the definitions and proxies the compiler just generated for this deployment — is served from the compilation's
 * memory.</p>
 *
 * <p>What the compilation generated beside its classes — the service entries under {@code META-INF}, which is
 * where core finds the records of a compilation — is served as resources. Core walks the directory a service
 * entry is in rather than open the entry by name, and a resource in memory has no directory to walk, so they are
 * written to a directory of their own on disk that is deleted with the deployment ({@link #dispose()}).</p>
 */
final class DeploymentClassLoader extends ClassLoader {

    private static final String CLASS_OUTPUT = "CLASS_OUTPUT/";

    private final Map<String, JavaFileObject> compiled = new LinkedHashMap<>();
    private final java.util.Set<String> childFirst = new java.util.HashSet<>();
    /**
     * The resources the compilation generated, on disk; {@code null} where it generated none.
     */
    private final @org.jspecify.annotations.Nullable Path resources;

    DeploymentClassLoader(Iterable<? extends JavaFileObject> generated, ClassLoader parent) {
        super(parent);
        Map<String, JavaFileObject> generatedResources = new LinkedHashMap<>();
        for (JavaFileObject file : generated) {
            if (file.getKind() == JavaFileObject.Kind.CLASS) {
                compiled.put(classNameOf(file), file);
            } else if (file.getKind() == JavaFileObject.Kind.OTHER) {
                String path = outputPathOf(file);
                if (path != null) {
                    generatedResources.put(path, file);
                }
            }
        }
        resources = writeResources(generatedResources);
        // everything generated that has a scenario class to live beside is defined there now, eagerly: a
        // class defined through a lookup is not found by name later, so anything the app loader will resolve
        // against — one generated class naming another — has to exist before it is asked for
        for (String name : List.copyOf(compiled.keySet())) {
            if (DEFINED_WITH_HOST.containsKey(name)) {
                continue;
            }
            try {
                if (parentHas(name)) {
                    // the shared compilation already produced this one, and the parent's copy is the one in
                    // the scenario classes' runtime package
                    continue;
                }
                Class<?> host = hostOf(name);
                if (host == null || host.getClassLoader() == null) {
                    continue;
                }
                defineBesideHost(name, host);
            } catch (LinkageError | RuntimeException e) {
                // left for findClass to serve from this loader instead
            }
        }
        // a class another deployment already defined beside its host with DIFFERENT bytes cannot be defined
        // again: it falls back to this loader, where the fresh bytes win over the stale shared copy
        for (String name : List.copyOf(DEFINED_WITH_HOST.keySet())) {
            JavaFileObject file = compiled.get(name);
            if (file == null) {
                continue;
            }
            Integer knownHash = DEFINED_WITH_HOST_HASH.get(name);
            if (knownHash == null) {
                continue;
            }
            try (InputStream in = file.openInputStream()) {
                if (java.util.Arrays.hashCode(in.readAllBytes()) != knownHash) {
                    childFirst.add(name);
                }
            } catch (IOException e) {
                // served as it comes
            }
        }
    }

    private boolean parentHas(String name) {
        try {
            Class.forName(name, false, getParent());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void defineBesideHost(String name, Class<?> host) {
        JavaFileObject file = compiled.get(name);
        if (file == null) {
            return;
        }
        byte[] bytes;
        try (InputStream in = file.openInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return;
        }
        try {
            Class<?> defined = java.lang.invoke.MethodHandles
                .privateLookupIn(host, java.lang.invoke.MethodHandles.lookup())
                .defineClass(bytes);
            DEFINED_WITH_HOST.put(name, defined);
            DEFINED_WITH_HOST_HASH.put(name, java.util.Arrays.hashCode(bytes));
        } catch (IllegalAccessException | LinkageError e) {
            // left for findClass to serve from this loader instead
        }
    }

    /**
     * The classes already defined beside their scenario classes, shared by every deployment: a class can be
     * defined into a loader once, and two deployments of one package generate the same definitions.
     */
    private static final Map<String, Class<?>> DEFINED_WITH_HOST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> DEFINED_WITH_HOST_HASH = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        JavaFileObject file = compiled.get(name);
        if (file == null) {
            throw new ClassNotFoundException(name);
        }
        Class<?> beside = childFirst.contains(name) ? null : DEFINED_WITH_HOST.get(name);
        if (beside != null) {
            return beside;
        }
        byte[] bytes;
        try (InputStream in = file.openInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    /**
     * The scenario class a generated class belongs to, when the parent can see it.
     */
    private java.lang.@org.jspecify.annotations.Nullable Class<?> hostOf(String name) {
        int dot = name.lastIndexOf('.');
        String packagePrefix = name.substring(0, dot + 1);
        String simple = name.substring(dot + 1);
        if (simple.startsWith("$")) {
            simple = simple.substring(1);
        }
        int inner = simple.indexOf('$');
        if (inner > 0) {
            simple = simple.substring(0, inner);
        }
        try {
            return Class.forName(packagePrefix + simple, false, getParent());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @Override
    protected @org.jspecify.annotations.Nullable URL findResource(String name) {
        if (resources == null) {
            return null;
        }
        Path resource = resources.resolve(name);
        if (!resource.normalize().startsWith(resources) || !Files.exists(resource)) {
            return null;
        }
        try {
            return resource.toUri().toURL();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL resource = findResource(name);
        return resource == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(resource));
    }

    /**
     * Deletes what the loader wrote to disk, once the deployment it loaded is taken down.
     */
    void dispose() {
        if (resources == null) {
            return;
        }
        try (java.util.stream.Stream<Path> tree = Files.walk(resources)) {
            for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // a directory left in the temporary files: the deployment is down all the same
        }
    }

    /**
     * Writes the generated resources to a directory of their own, so that they have a directory to be walked.
     */
    private static @org.jspecify.annotations.Nullable Path writeResources(Map<String, JavaFileObject> generated) {
        if (generated.isEmpty()) {
            return null;
        }
        try {
            Path directory = Files.createTempDirectory("cdi-tck-deployment");
            for (Map.Entry<String, JavaFileObject> entry : generated.entrySet()) {
                Path target = directory.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                try (InputStream in = entry.getValue().openInputStream()) {
                    Files.copy(in, target);
                }
            }
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("The resources of the deployment could not be written", e);
        }
    }

    /**
     * The names of the compiled classes, which is where the definitions of the deployment are found.
     *
     * @return The class names
     */
    Iterable<String> compiledClassNames() {
        return compiled.keySet();
    }

    private static String classNameOf(JavaFileObject file) {
        // the compiler's output lives at mem:///CLASS_OUTPUT/pkg/Name.class
        String name = file.getName();
        int output = name.indexOf(CLASS_OUTPUT);
        name = name.substring(output + CLASS_OUTPUT.length(), name.length() - ".class".length());
        return name.replace('/', '.');
    }

    /**
     * The path of a generated resource below the class output, or {@code null} for a file generated elsewhere.
     */
    private static @org.jspecify.annotations.Nullable String outputPathOf(JavaFileObject file) {
        // a resource of the compilation lives at mem:///CLASS_OUTPUT/META-INF/micronaut/service/entry
        String name = file.getName();
        int output = name.indexOf(CLASS_OUTPUT);
        return output < 0 ? null : name.substring(output + CLASS_OUTPUT.length());
    }
}
