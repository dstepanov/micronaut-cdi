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

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The classes a compilation in memory wrote, by binary name: what the tests that compile with {@code JavaParser} read
 * back from its output, and load through a loader that asks its parent first.
 */
public final class CompiledClasses {

    private static final String CLASS_OUTPUT = "/CLASS_OUTPUT/";

    private final Map<String, byte[]> classes;

    private CompiledClasses(Map<String, byte[]> classes) {
        this.classes = classes;
    }

    /**
     * The classes among the files a compilation wrote, in the order it wrote them.
     *
     * @param files What the compilation wrote
     * @return The classes
     */
    public static CompiledClasses of(Iterable<? extends JavaFileObject> files) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (JavaFileObject file : files) {
            if (file.getKind() == JavaFileObject.Kind.CLASS) {
                try (InputStream in = file.openInputStream()) {
                    classes.put(binaryName(file), in.readAllBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return new CompiledClasses(classes);
    }

    /**
     * The binary name of a class file the compilation wrote, read from its path under the class output.
     *
     * @param file The class file
     * @return The binary name
     */
    public static String binaryName(JavaFileObject file) {
        String name = file.getName();
        return name.substring(name.indexOf(CLASS_OUTPUT) + CLASS_OUTPUT.length(),
            name.length() - ".class".length()).replace('/', '.');
    }

    /**
     * The binary names of the classes, in the order they were written.
     *
     * @return The names
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(classes.keySet());
    }

    /**
     * A loader of the classes that asks its parent first, and defines one of these only where the parent has no
     * class of its name.
     *
     * @param parent The parent loader
     * @return The loader
     */
    public ClassLoader loader(ClassLoader parent) {
        return new ClassLoader(parent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
