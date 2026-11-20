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
package org.example.cdi.se;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An implementation that does not support CDI Full is required to detect an archive whose {@code META-INF/beans.xml}
 * declares {@code bean-discovery-mode="all"} and to treat it as a deployment problem (CDI 5.0, section 2.5.1 of CDI
 * Lite), whichever way the container is started: through the SE bootstrap or as a Micronaut application context.
 */
class AllDiscoveryArchiveTest {

    private static final String ALL =
        "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"all\" version=\"4.0\"/>";

    @TempDir
    Path directory;

    @Test
    void anArchiveThatDiscoversAllFailsAnApplicationContextAsItStarts() throws Exception {
        try (URLClassLoader loader = loaderOf(archive("all.jar", ALL))) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                () -> ApplicationContext.builder(loader).start().close());
            DeploymentException problem = deploymentProblemIn(failure);
            assertTrue(problem.getMessage().contains("all.jar"), problem.getMessage());
        }
    }

    @Test
    void anArchiveThatDiscoversAllFailsAnSeContainerAsItStarts() throws Exception {
        try (URLClassLoader loader = loaderOf(archive("all.jar", ALL))) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> {
                try (SeContainer container = SeContainerInitializer.newInstance().setClassLoader(loader).initialize()) {
                    container.isRunning();
                }
            });
            deploymentProblemIn(failure);
        }
    }

    @Test
    void theOtherModesAndACommentedOutModeStart() throws Exception {
        for (String beansXml : new String[] {
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"annotated\" version=\"4.0\"/>",
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"none\" version=\"4.0\"/>",
            "<!-- bean-discovery-mode=\"all\" --><beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"4.0\"/>",
            // the schema's enumeration is case sensitive: ALL names no mode of it, CDI Full's or another
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"ALL\" version=\"4.0\"/>",
            ""}) {
            // an archive of its own each: the JDK caches an opened jar by its URL
            try (URLClassLoader loader = loaderOf(archive("other-" + beansXml.hashCode() + ".jar", beansXml));
                 ApplicationContext context = ApplicationContext.builder(loader).start()) {
                assertTrue(context.isRunning(), beansXml);
            }
        }
    }

    private static DeploymentException deploymentProblemIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeploymentException problem) {
                return problem;
            }
        }
        assertNotNull(null, "a deployment problem, got: " + failure);
        throw new AssertionError(failure);
    }

    private static URLClassLoader loaderOf(Path archive) throws IOException {
        return new URLClassLoader(new URL[] {archive.toUri().toURL()}, AllDiscoveryArchiveTest.class.getClassLoader());
    }

    private Path archive(String name, String beansXml) throws IOException {
        Path jar = directory.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/beans.xml"));
            out.write(beansXml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
