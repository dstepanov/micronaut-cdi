package io.micronaut.cdi.processor.extension;

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiler options that name the build compatible extensions of a compilation: an extension the service
 * loader finds runs unless the options say the named ones are all of them, and a named one runs whether or not
 * it is registered as a service.
 */
class ExtensionOptionsTest {

    private static final String SOURCE = """
        package options;

        @jakarta.enterprise.context.Dependent
        public class Subject {
        }
        """;

    @Test
    void theServiceLoadedExtensionsRunWhenNoneIsNamed() {
        assertEquals(List.of("Registered ran"), ranWith(null, null));
    }

    @Test
    void aNamedExtensionRunsBesideTheServiceLoadedOnes() {
        assertEquals(List.of("Named ran", "Registered ran"), ranWith(Named.class.getName(), null));
    }

    @Test
    void theNamedExtensionsAreAllOfThemWhenTheOptionsSaySo() {
        assertEquals(List.of("Named ran"), ranWith(Named.class.getName(), "true"));
    }

    @Test
    void anExtensionBothNamedAndRegisteredRunsOnce() {
        assertEquals(List.of("Named ran", "Registered ran"),
            ranWith(Registered.class.getName() + ", " + Named.class.getName() + ",", null));
    }

    @Test
    void noExtensionRunsWhenNoneIsNamedAndTheNamedOnesAreAllOfThem() {
        assertEquals(List.of(), ranWith(null, "true"));
    }

    @Test
    void theOptionsOfOneCompilationAreNotTheNextOnes() {
        assertEquals(List.of("Named ran"), ranWith(Named.class.getName(), "true"));
        assertEquals(List.of("Registered ran"), ranWith(null, null));
    }

    @Test
    void theOptionsOfAFailedCompilationAreNotTheNextOnes() {
        RuntimeException failed = assertThrows(RuntimeException.class,
            () -> ranWith(Failing.class.getName(), "true"));
        assertTrue(String.valueOf(failed.getMessage()).contains("Failing failed"),
            "the compilation fails with the error the extension reported: " + failed.getMessage());
        assertEquals(List.of("Registered ran"), ranWith(null, null));
    }

    /**
     * Compiles the subject with the given options and returns what the discovery phases of the extensions that
     * ran reported, sorted. Nothing is cleaned up between compilations: Micronaut's processor copies the
     * micronaut.* options into the system properties as it initializes and restores them as it finishes, which is
     * what keeps one compilation's options from being the next one's.
     */
    private static List<String> ranWith(String extensions, String only) {
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                if (extensions != null) {
                    options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + extensions);
                }
                if (only != null) {
                    options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=" + only);
                }
                return options;
            }
        }) {
            parser.generate("options.Subject", SOURCE);
            List<String> ran = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : parser.getDiagnosticCollector().getDiagnostics()) {
                String message = diagnostic.getMessage(null);
                if (message.endsWith(" ran")) {
                    ran.add(message);
                }
            }
            ran.sort(null);
            return ran;
        }
    }

    /**
     * An extension the options name, registered as no service.
     */
    public static final class Named implements BuildCompatibleExtension {
        @Discovery
        public void discover(Messages messages) {
            messages.info("Named ran");
        }
    }

    /**
     * An extension the options name, registered as no service, that fails the compilation.
     */
    public static final class Failing implements BuildCompatibleExtension {
        @Discovery
        public void discover(Messages messages) {
            messages.error("Failing failed");
        }
    }

    /**
     * An extension registered as a service in the test resources of this module.
     */
    public static final class Registered implements BuildCompatibleExtension {
        @Discovery
        public void discover(Messages messages) {
            messages.info("Registered ran");
        }
    }
}
