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
package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesis (section 2.10.5) and validation (section 2.10.6) wait for every bean of the compilation to have been
 * registered, a bean another annotation processor generates as a source in a later round included; and a class the
 * discovery phase scanned that another processor generates rounds later is imported once it is available.
 */
class LaterRoundBeanTest {

    private static final String EARLY = """
        package later;

        @jakarta.enterprise.context.Dependent
        public class Early {
        }
        """;

    private static final String BEAN = """
        package later;

        @jakarta.enterprise.context.Dependent
        public class Late {
        }
        """;

    private static final String MIDDLE = """
        package later;

        @jakarta.enterprise.context.Dependent
        public class Middle {
        }
        """;

    private static final String PLAIN = """
        package later;

        public class Late {
        }
        """;

    @Test
    void aBeanGeneratedInALaterRoundIsRegisteredBeforeSynthesisAndValidation() {
        Recording.EVENTS.clear();
        try (JavaParser parser = new Generating(Recording.class, Map.of(1, BEAN))) {
            parser.generate("later.Early", EARLY);
        }
        assertEquals(List.of("register later.Early", "register later.Late", "synthesis", "validation"),
            Recording.EVENTS);
    }

    @Test
    void aBeanIsSynthesisedFromABeanGeneratedInALaterRound() {
        try (ApplicationContext context = ExtensionDeployment.start(
            new Generating(Accompanying.class, Map.of(1, BEAN)), "later.Early", EARLY)) {
            assertEquals("later.Late", context.getBean(Companion.class).of());
        }
    }

    @Test
    void aValidationErrorAboutABeanGeneratedInALaterRoundFailsTheCompilation() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (JavaParser parser = new Generating(Rejecting.class, Map.of(1, BEAN))) {
                parser.generate("later.Early", EARLY);
            }
        });
        assertTrue(failure.getMessage().contains("rejected later.Late"), failure.getMessage());
    }

    @Test
    void aScannedClassGeneratedTwoRoundsLaterIsImportedOnceAvailable() {
        Scanning.EVENTS.clear();
        // Middle is generated as the first round is processed and Late as the second is: Late is available in the
        // third round, after the first import of the scanned classes was written
        try (JavaParser parser = new Generating(Scanning.class, Map.of(1, MIDDLE, 2, PLAIN))) {
            parser.generate("later.Early", EARLY);
        }
        assertEquals(List.of("register later.Early", "register later.Middle", "register later.Late"),
            Scanning.EVENTS);
    }

    @Test
    void aScannedClassThatIsNeverFoundFailsTheCompilation() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (JavaParser parser = new Generating(ScanningMissing.class, Map.of())) {
                parser.generate("later.Early", EARLY);
            }
        });
        assertTrue(failure.getMessage().contains("later.Missing"), failure.getMessage());
    }

    /**
     * Records the order the phases see the beans of the package {@code later} in.
     */
    public static final class Recording implements BuildCompatibleExtension {

        static final List<String> EVENTS = new ArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            String name = bean.declaringClass().name();
            if (name.startsWith("later.")) {
                EVENTS.add("register " + name);
            }
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            EVENTS.add("synthesis");
        }

        @Validation
        public void validate() {
            EVENTS.add("validation");
        }
    }

    /**
     * Describes a companion of the bean {@code later.Late}, which a processor generates in a later round.
     */
    public static final class Accompanying implements BuildCompatibleExtension {

        private final List<String> late = new ArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.declaringClass().name().equals("later.Late")) {
                late.add(bean.declaringClass().name());
            }
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            for (String of : late) {
                components.addBean(Companion.class).type(Companion.class).withParam("of", of)
                    .createWith(CompanionCreator.class);
            }
        }
    }

    /**
     * Rejects the bean {@code later.Late}, which a processor generates in a later round.
     */
    public static final class Rejecting implements BuildCompatibleExtension {

        private final List<BeanInfo> late = new ArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.declaringClass().name().equals("later.Late")) {
                late.add(bean);
            }
        }

        @Validation
        public void validate(Messages messages) {
            for (BeanInfo bean : late) {
                messages.error("rejected " + bean.declaringClass().name(), bean);
            }
        }
    }

    /**
     * Scans the class {@code later.Late}, which says nothing about itself, and records what is registered.
     */
    public static final class Scanning implements BuildCompatibleExtension {

        static final List<String> EVENTS = new ArrayList<>();

        @Discovery
        public void scan(ScannedClasses scanned) {
            scanned.add("later.Late");
        }

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            String name = bean.declaringClass().name();
            if (name.startsWith("later.")) {
                EVENTS.add("register " + name);
            }
        }
    }

    /**
     * Scans a class that no compilation has.
     */
    public static final class ScanningMissing implements BuildCompatibleExtension {

        @Discovery
        public void scan(ScannedClasses scanned) {
            scanned.add("later.Missing");
        }
    }

    /**
     * What {@link Accompanying} describes.
     *
     * @param of The bean it accompanies
     */
    public record Companion(String of) {
    }

    /**
     * Creates a companion from its parameter.
     */
    public static final class CompanionCreator implements SyntheticBeanCreator<Companion> {

        @Override
        public Companion create(Instance<Object> lookup, Parameters params) {
            return new Companion(params.get("of", String.class));
        }
    }

    /**
     * A processor of another library, which generates a source as a given round is processed: javac compiles and
     * visits it in the round after.
     */
    static final class LaterSourceProcessor extends AbstractProcessor {

        private final Map<Integer, String> sources;
        private int round;

        LaterSourceProcessor(Map<Integer, String> sources) {
            this.sources = sources;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            String source = sources.get(++round);
            if (source == null) {
                return false;
            }
            String name = source.substring(source.indexOf("class ") + "class ".length(), source.indexOf(" {"));
            try (Writer writer = processingEnv.getFiler().createSourceFile("later." + name).openWriter()) {
                writer.write(source);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return false;
        }
    }

    /**
     * A compilation that runs one extension alone, with {@link LaterSourceProcessor} beside Micronaut's processors.
     */
    private static final class Generating extends JavaParser {

        private final Class<? extends BuildCompatibleExtension> extension;
        private final Map<Integer, String> sources;

        Generating(Class<? extends BuildCompatibleExtension> extension, Map<Integer, String> sources) {
            this.extension = extension;
            this.sources = sources;
        }

        @Override
        protected Set<String> getCompilerOptions() {
            Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
            options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + extension.getName());
            options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
            return options;
        }

        @Override
        protected List<Processor> getAnnotationProcessors() {
            List<Processor> processors = new ArrayList<>();
            processors.add(new LaterSourceProcessor(sources));
            processors.addAll(super.getAnnotationProcessors());
            return processors;
        }
    }
}
