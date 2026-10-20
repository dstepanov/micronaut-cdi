package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A compilation that names its extensions through the processor's options leaves the system properties as it found
 * them: Micronaut's processor copies the options there as it initializes and restores what was there before as it
 * finishes, and the harness restores the same, a value set before the compilation included.
 */
class ExtensionCompilationOptionsTest {

    public static class Quiet implements BuildCompatibleExtension {
    }

    @Test
    void aValueSetBeforeTheCompilationIsThereAfterIt() {
        String only = BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION;
        String extensions = BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION;
        String previousOnly = System.getProperty(only);
        String previousExtensions = System.getProperty(extensions);
        System.setProperty(only, "false");
        System.clearProperty(extensions);
        try {
            try (JavaParser parser = ExtensionCompilation.of(Quiet.class)) {
                parser.generate("options.Subject", """
                    package options;
                    @jakarta.enterprise.context.Dependent
                    public class Subject {
                    }
                    """);
            }
            assertEquals("false", System.getProperty(only), "the value set before the compilation is restored");
            assertNull(System.getProperty(extensions), "a property absent before the compilation is absent after it");
        } finally {
            restore(only, previousOnly);
            restore(extensions, previousExtensions);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
