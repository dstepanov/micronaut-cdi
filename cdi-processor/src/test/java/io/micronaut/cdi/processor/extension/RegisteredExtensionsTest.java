package io.micronaut.cdi.processor.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extension registered as a service that cannot be had fails the compilation rather than being left out: its
 * phases would otherwise not run, and the compilation would not even be aggregating.
 */
class RegisteredExtensionsTest {

    private static final String SERVICE =
        "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @Test
    void aServiceEntryNamingAnAbsentExtensionFails(@TempDir Path root) throws IOException {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> registeredWith(root, "absent.Extension"));
        assertTrue(failure.getMessage().contains("absent.Extension"), failure.getMessage());
    }

    @Test
    void aServiceEntryNamingAnExtensionWithoutAConstructorWithoutParametersFails(@TempDir Path root)
        throws IOException {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> registeredWith(root, WithoutDefaultConstructor.class.getName()));
        assertTrue(failure.getMessage().contains(WithoutDefaultConstructor.class.getName()), failure.getMessage());
    }

    @Test
    void aServiceEntryNamingAnExtensionLoadsIt(@TempDir Path root) throws IOException {
        // the one of the test resources of this module is found through the parent loader too
        List<String> names = registeredWith(root, ExtensionOptionsTest.Named.class.getName()).stream()
            .map(extension -> extension.getClass().getName())
            .sorted()
            .toList();
        assertEquals(List.of(ExtensionOptionsTest.Named.class.getName(),
            ExtensionOptionsTest.Registered.class.getName()), names);
    }

    private static List<jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension> registeredWith(
        Path root, String entry) throws IOException {
        Path service = root.resolve(SERVICE);
        Files.createDirectories(service.getParent());
        Files.writeString(service, entry + "\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
            RegisteredExtensionsTest.class.getClassLoader())) {
            return ExtensionMethods.registered(loader);
        }
    }

    /**
     * An extension the service loader cannot create.
     */
    public static final class WithoutDefaultConstructor
        implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
        public WithoutDefaultConstructor(String required) {
        }
    }
}
