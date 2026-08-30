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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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
 */
final class DeploymentClassLoader extends ClassLoader {

    private final Map<String, JavaFileObject> compiled = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final java.util.Set<String> childFirst = new java.util.HashSet<>();

    DeploymentClassLoader(Iterable<? extends JavaFileObject> generated, ClassLoader parent) {
        super(parent);
        for (JavaFileObject file : generated) {
            if (file.getKind() == JavaFileObject.Kind.CLASS) {
                compiled.put(classNameOf(file), file);
            }
        }
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
     * Adds a resource of the deployment — a service entry of its archive, say — so that whatever reads
     * resources through this loader sees it.
     *
     * @param name  The resource name
     * @param bytes Its content
     */
    void addResource(String name, byte[] bytes) {
        resources.put(name, bytes);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] bytes = resources.get(name);
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        byte[] bytes = resources.get(name);
        Enumeration<URL> parent = super.getResources(name);
        if (bytes == null) {
            return parent;
        }
        // written out so that it has a URL to be read back from: a ServiceLoader reads service entries
        // through getResources, and a URL needs somewhere to point
        java.nio.file.Path file = java.nio.file.Files.createTempFile("deployment-resource-", ".txt");
        java.nio.file.Files.write(file, bytes);
        file.toFile().deleteOnExit();
        List<URL> all = new java.util.ArrayList<>(Collections.list(parent));
        all.add(file.toUri().toURL());
        return Collections.enumeration(all);
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
        int output = name.indexOf("CLASS_OUTPUT/");
        name = name.substring(output + "CLASS_OUTPUT/".length(), name.length() - ".class".length());
        return name.replace('/', '.');
    }
}
