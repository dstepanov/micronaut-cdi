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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Synthesis (section 2.10.5) and validation (section 2.10.6) wait for every bean of the compilation to have been
 * registered, a bean another annotation processor generates as a source in a later round included.
 */
class LaterRoundBeanTest {

    @Test
    void aBeanGeneratedInALaterRoundIsRegisteredBeforeSynthesisAndValidation() {
        Recording.EVENTS.clear();
        try (JavaParser parser = new WithAGeneratingProcessor()) {
            parser.generate("later.Early", """
                package later;

                @jakarta.enterprise.context.Dependent
                public class Early {
                }
                """);
        }
        assertEquals(List.of("register later.Early", "register later.Late", "synthesis", "validation"),
            Recording.EVENTS);
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
     * A processor of another library, which generates the source of a bean as the first round is processed: javac
     * compiles and visits it in the second round.
     */
    static final class LateSourceProcessor extends AbstractProcessor {

        private boolean generated;

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
            if (generated || roundEnv.processingOver()) {
                return false;
            }
            generated = true;
            try (Writer writer = processingEnv.getFiler().createSourceFile("later.Late").openWriter()) {
                writer.write("""
                    package later;

                    @jakarta.enterprise.context.Dependent
                    public class Late {
                    }
                    """);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return false;
        }
    }

    /**
     * A compilation that runs {@link Recording} alone, with {@link LateSourceProcessor} beside Micronaut's
     * processors.
     */
    private static final class WithAGeneratingProcessor extends JavaParser {

        @Override
        protected Set<String> getCompilerOptions() {
            Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
            options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + Recording.class.getName());
            options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
            return options;
        }

        @Override
        protected List<Processor> getAnnotationProcessors() {
            List<Processor> processors = new ArrayList<>();
            processors.add(new LateSourceProcessor());
            processors.addAll(super.getAnnotationProcessors());
            return processors;
        }
    }
}
