package io.micronaut.cdi.processor.extension;

import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micronaut's annotation processor is aggregating for Gradle's incremental compilation only where the compilation
 * has build compatible extensions, whose late phases read the whole compilation; one without them stays isolating.
 */
class CompilationAggregationTest {

    private static final String AGGREGATING = "org.gradle.annotation.processing.aggregating";
    private static final String ISOLATING = "org.gradle.annotation.processing.isolating";

    private static final String SOURCE = """
        package aggregation;

        @jakarta.enterprise.context.Dependent
        public class Subject {
        }
        """;

    @Test
    void aCompilationWithExtensionsIsAggregating() {
        // the extension registered as a service in the test resources of this module
        Set<String> options = supportedOptionsWith(null);
        assertTrue(options.contains(AGGREGATING), String.valueOf(options));
        assertFalse(options.contains(ISOLATING), String.valueOf(options));
    }

    @Test
    void aCompilationWithANamedExtensionOnlyIsAggregating() {
        Set<String> options = supportedOptionsWith(ExtensionOptionsTest.Named.class.getName());
        assertTrue(options.contains(AGGREGATING), String.valueOf(options));
    }

    @Test
    void aCompilationWithoutExtensionsStaysIsolating() {
        Set<String> options = supportedOptionsWith("");
        assertTrue(options.contains(ISOLATING), String.valueOf(options));
        assertFalse(options.contains(AGGREGATING), String.valueOf(options));
    }

    /**
     * Compiles the subject and returns the options the isolating processor of Micronaut then declares, which is
     * where Gradle reads the kind of incremental processing from.
     *
     * @param onlyNamed The extensions the compilation runs and no others (empty for none), or {@code null} for the
     *                  service-loaded ones
     */
    private static Set<String> supportedOptionsWith(String onlyNamed) {
        TypeElementVisitorProcessor[] processor = new TypeElementVisitorProcessor[1];
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
                // what the Micronaut Gradle plugin sets for an incremental compilation
                options.add("-Amicronaut.processing.incremental=true");
                if (onlyNamed != null) {
                    if (!onlyNamed.isEmpty()) {
                        options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + onlyNamed);
                    }
                    options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
                }
                return options;
            }

            @Override
            protected TypeElementVisitorProcessor getTypeElementVisitorProcessor() {
                processor[0] = super.getTypeElementVisitorProcessor();
                return processor[0];
            }
        }) {
            parser.generate("aggregation.Subject", SOURCE);
            assertEquals(Set.of("*").equals(processor[0].getSupportedAnnotationTypes()),
                processor[0].getSupportedOptions().contains(AGGREGATING),
                "the processor claims every class exactly when it is aggregating");
            return processor[0].getSupportedOptions();
        }
    }
}
