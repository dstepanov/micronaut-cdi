package org.example.cdi.se;

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library whose {@code META-INF/beans.xml} says {@code bean-discovery-mode="none"} is not a bean archive
 * (CDI 4.1 section 2.5.1): a class of it compiled with the processor is no bean of a container the SE bootstrap
 * discovers, while the same library with an archive of the annotated mode, or with no {@code beans.xml} at all,
 * contributes it.
 */
class SeNoneDiscoveryArchiveTest {

    private static final String CLASS_OUTPUT = "/CLASS_OUTPUT/";

    private static final String SOURCE = """
        package nodiscovery;

        @jakarta.enterprise.context.Dependent
        public class Library {
        }
        """;

    @TempDir
    Path directory;

    @Test
    void aClassOfAnArchiveThatDiscoversNothingIsNoBean() throws Exception {
        assertFalse(isBean(archive("none.jar",
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"none\" version=\"4.0\"/>")));
        assertTrue(isBean(archive("annotated.jar",
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"annotated\" version=\"4.0\"/>")));
        assertTrue(isBean(archive("plain.jar", null)));
    }

    @Test
    void aClassOfAnArchiveThatDiscoversNothingAddedToTheSyntheticArchiveIsABean() throws Exception {
        Path archive = archive("none.jar",
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"none\" version=\"4.0\"/>");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {archive.toUri().toURL()},
            SeNoneDiscoveryArchiveTest.class.getClassLoader())) {
            Class<?> library = loader.loadClass("nodiscovery.Library");
            try (SeContainer container = SeContainerInitializer.newInstance().setClassLoader(loader)
                .addBeanClasses(library).initialize()) {
                assertTrue(container.select(library).isResolvable());
            }
        }
    }

    private static boolean isBean(Path archive) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {archive.toUri().toURL()},
            SeNoneDiscoveryArchiveTest.class.getClassLoader())) {
            Class<?> library = loader.loadClass("nodiscovery.Library");
            try (SeContainer container = SeContainerInitializer.newInstance().setClassLoader(loader).initialize()) {
                return container.select(library).isResolvable();
            }
        }
    }

    private Path archive(String name, String beansXml) throws IOException {
        Path jar = directory.resolve(name);
        try (JavaParser parser = new JavaParser();
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            java.util.Set<String> directories = new java.util.HashSet<>(java.util.Set.of("META-INF/"));
            out.putNextEntry(new JarEntry("META-INF/"));
            out.closeEntry();
            for (JavaFileObject file : parser.generate("nodiscovery.Library", SOURCE)) {
                String path = file.getName();
                int output = path.indexOf(CLASS_OUTPUT);
                if (output < 0) {
                    continue;
                }
                String entry = path.substring(output + CLASS_OUTPUT.length());
                // the directories too, which Micronaut lists the service entries of a jar by
                for (int slash = entry.indexOf('/'); slash > 0; slash = entry.indexOf('/', slash + 1)) {
                    if (directories.add(entry.substring(0, slash + 1))) {
                        out.putNextEntry(new JarEntry(entry.substring(0, slash + 1)));
                        out.closeEntry();
                    }
                }
                out.putNextEntry(new JarEntry(entry));
                try (InputStream in = file.openInputStream()) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
            if (beansXml != null) {
                out.putNextEntry(new JarEntry("META-INF/beans.xml"));
                out.write(beansXml.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
