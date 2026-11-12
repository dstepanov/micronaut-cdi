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

import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.core.type.TypeHierarchy;
import io.micronaut.inject.BeanDefinitionReference;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader of a compiled deployment serves what the compilation generated beside its classes: the service
 * entries under {@code META-INF}, which is where core finds the records of a compilation.
 */
class DeploymentClassLoaderTest {

    private static final String TYPE_HIERARCHY_SERVICE = "META-INF/micronaut/io.micronaut.core.type.TypeHierarchy";

    @Test
    void servesTheTypeHierarchyRecordsOfWhatItCompiled() throws Exception {
        Iterable<? extends javax.tools.JavaFileObject> generated = DeploymentCompiler.compile(Map.of(
            "tck.loader.StringList",
            """
                package tck.loader;

                @jakarta.enterprise.context.Dependent
                public class StringList extends java.util.ArrayList<String> {
                }
                """));
        ClassLoader parent = getClass().getClassLoader();
        assertTrue(TypeHierarchies.find("tck.loader.StringList", parent).isEmpty(),
            "the parent was not compiled with the class");
        DeploymentClassLoader loader = new DeploymentClassLoader(generated, parent);
        try {
            List<URL> serviceDirectories = Collections.list(loader.getResources(TYPE_HIERARCHY_SERVICE));
            assertTrue(serviceDirectories.stream().anyMatch(url -> url.getPath().contains("cdi-tck-deployment")),
                "the loader serves the service directory it compiled, beside its parent's: " + serviceDirectories);

            Optional<TypeHierarchy> record = TypeHierarchies.find("tck.loader.StringList", loader);
            assertTrue(record.isPresent(), "the record of the compilation is found through the loader");
            assertEquals("tck.loader.StringList", record.get().getDeclaration().getType().getName());
        } finally {
            loader.dispose();
        }
        assertTrue(Collections.list(loader.getResources(TYPE_HIERARCHY_SERVICE)).stream()
                .noneMatch(url -> url.getPath().contains("cdi-tck-deployment")),
            "what the loader wrote is deleted with it");
    }

    @Test
    void servesItsOwnDefinitionOfAClassTheParentCompiledOtherwise() throws Exception {
        String source = """
            package tck.loader.versioned;

            @jakarta.enterprise.context.Dependent
            @jakarta.inject.Named("%s")
            public class Versioned {
            }
            """;
        ClassLoader parent = getClass().getClassLoader();
        DeploymentClassLoader first = new DeploymentClassLoader(DeploymentCompiler.compile(Map.of(
            "tck.loader.versioned.Versioned", source.formatted("first"))), parent);
        DeploymentClassLoader second = new DeploymentClassLoader(DeploymentCompiler.compile(Map.of(
            "tck.loader.versioned.Versioned", source.formatted("second"))), first);
        try {
            String reference = "tck.loader.versioned.$Versioned$Definition";
            assertEquals("first", nameOf(first, reference));
            assertEquals("second", nameOf(second, reference),
                "the second deployment's metadata differs from what its parent holds, and is the one served");
        } finally {
            second.dispose();
            first.dispose();
        }
    }

    @Test
    void refusesAClassItCannotTellFromItsParentsCopy() throws Exception {
        String source = """
            package tck.loader.unreadable;

            @jakarta.enterprise.context.Dependent
            public class Unreadable {
            }
            """;
        ClassLoader parent = getClass().getClassLoader();
        DeploymentClassLoader first = new DeploymentClassLoader(DeploymentCompiler.compile(Map.of(
            "tck.loader.unreadable.Unreadable", source)), parent);
        try {
            List<javax.tools.JavaFileObject> unreadable = new java.util.ArrayList<>();
            for (javax.tools.JavaFileObject file : DeploymentCompiler.compile(Map.of(
                "tck.loader.unreadable.Unreadable", source))) {
                unreadable.add(file.getKind() == javax.tools.JavaFileObject.Kind.CLASS
                    && file.getName().contains("$Unreadable$Definition")
                    ? new javax.tools.ForwardingJavaFileObject<>(file) {
                        @Override
                        public java.io.InputStream openInputStream() throws java.io.IOException {
                            throw new java.io.IOException("unreadable");
                        }
                    } : file);
            }
            assertThrows(DeploymentClassLoader.UnreadableClass.class,
                () -> new DeploymentClassLoader(unreadable, first));
        } finally {
            first.dispose();
        }
    }

    private static String nameOf(ClassLoader loader, String referenceClass) throws Exception {
        Class<?> type = loader.loadClass(referenceClass);
        BeanDefinitionReference<?> reference =
            (BeanDefinitionReference<?>) type.getDeclaredConstructor().newInstance();
        return reference.getAnnotationMetadata().stringValue("jakarta.inject.Named").orElse("");
    }
}
