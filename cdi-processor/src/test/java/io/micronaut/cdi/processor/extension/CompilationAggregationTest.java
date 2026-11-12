package io.micronaut.cdi.processor.extension;

import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micronaut's annotation processor is aggregating for Gradle's incremental compilation only where the compilation
 * has build compatible extensions, whose late phases read the whole compilation; one without them stays isolating.
 *
 * <p>The options are read as Gradle reads them: right after the processor is initialized, before any class is
 * processed. Each compilation decides for itself, so a compilation that gains or loses its extensions - the ones the
 * service loader finds, or the ones the options name - changes its kind, and the one after it is not affected.</p>
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

    /**
     * The extensions of a compilation: the service-loaded ones (the extension registered in the test resources of
     * this module), one named by the options and no others, or none at all.
     */
    private enum Extensions {
        SERVICE_LOADED(null),
        NAMED(ExtensionOptionsTest.Named.class.getName()),
        ABSENT("");

        private final String onlyNamed;

        Extensions(String onlyNamed) {
            this.onlyNamed = onlyNamed;
        }
    }

    @Test
    void aCompilationWithServiceLoadedExtensionsIsAggregating() {
        assertAggregating(Extensions.SERVICE_LOADED);
    }

    @Test
    void aCompilationWithANamedExtensionOnlyIsAggregating() {
        assertAggregating(Extensions.NAMED);
    }

    @Test
    void aCompilationWithoutExtensionsStaysIsolating() {
        assertIsolating(Extensions.ABSENT);
    }

    @Test
    void eachCompilationDecidesFromItsOwnExtensions() {
        // every transition between the three, one compilation after the other in the same process
        assertAggregating(Extensions.SERVICE_LOADED);
        assertIsolating(Extensions.ABSENT);
        assertAggregating(Extensions.NAMED);
        assertIsolating(Extensions.ABSENT);
        assertAggregating(Extensions.SERVICE_LOADED);
        assertAggregating(Extensions.NAMED);
        assertAggregating(Extensions.SERVICE_LOADED);
        assertIsolating(Extensions.ABSENT);
        assertIsolating(Extensions.ABSENT);
    }

    private static void assertAggregating(Extensions extensions) {
        Set<String> options = supportedOptionsWith(extensions);
        assertTrue(options.contains(AGGREGATING), extensions + ": " + options);
        assertFalse(options.contains(ISOLATING), extensions + ": " + options);
    }

    private static void assertIsolating(Extensions extensions) {
        Set<String> options = supportedOptionsWith(extensions);
        assertTrue(options.contains(ISOLATING), extensions + ": " + options);
        assertFalse(options.contains(AGGREGATING), extensions + ": " + options);
    }

    /**
     * Compiles the subject and returns the options the isolating processor of Micronaut declared as it was
     * initialized, which is when Gradle reads the kind of incremental processing, together with the annotation
     * types it then claimed.
     */
    private static Set<String> supportedOptionsWith(Extensions extensions) {
        Set<String> options = new LinkedHashSet<>();
        Set<String> annotationTypes = new LinkedHashSet<>();
        try (JavaParser parser = new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> compilerOptions = new LinkedHashSet<>(super.getCompilerOptions());
                // what the Micronaut Gradle plugin sets for an incremental compilation
                compilerOptions.add("-Amicronaut.processing.incremental=true");
                if (extensions.onlyNamed != null) {
                    if (!extensions.onlyNamed.isEmpty()) {
                        compilerOptions.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "="
                            + extensions.onlyNamed);
                    }
                    compilerOptions.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
                }
                return compilerOptions;
            }

            @Override
            protected TypeElementVisitorProcessor getTypeElementVisitorProcessor() {
                return new TypeElementVisitorProcessor() {
                    @Override
                    public synchronized void init(ProcessingEnvironment processingEnv) {
                        super.init(processingEnv);
                        options.addAll(getSupportedOptions());
                        annotationTypes.addAll(getSupportedAnnotationTypes());
                    }
                };
            }
        }) {
            parser.generate("aggregation.Subject", SOURCE);
        }
        assertFalse(options.isEmpty(), "the processor was initialized");
        assertEquals(Set.of("*").equals(annotationTypes), options.contains(AGGREGATING),
            "the processor claims every class exactly when it is aggregating");
        return options;
    }
}
