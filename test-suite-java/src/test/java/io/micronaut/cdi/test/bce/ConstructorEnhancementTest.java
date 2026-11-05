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
import io.micronaut.cdi.test.CompiledClasses;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An enhancement method that takes a {@link MethodConfig} is called once for each constructor and each method the
 * discovered type declares ({@code @Enhancement}, CDI 4.1): a constructor is configured as a method is.
 */
class ConstructorEnhancementTest {

    /**
     * What the enhancement writes on each constructor or method it is handed.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Enhanced {
        String value();
    }

    @Test
    void anEnhancementOfMethodsIsHandedTheConstructorsToo() throws ReflectiveOperationException {
        BeanDefinition<?> definition;
        try (JavaParser parser = ExtensionCompilation.of(MarksEveryMethod.class)) {
            ClassLoader loader = CompiledClasses.of(parser.generate("engines.Engine", """
                    package engines;

                    @jakarta.enterprise.context.Dependent
                    public class Engine {

                        public Engine() {
                        }

                        public void run() {
                        }
                    }
                    """))
                .loader(ConstructorEnhancementTest.class.getClassLoader());
            definition = ((BeanDefinitionReference<?>) loader.loadClass("engines.$Engine$Definition")
                .getDeclaredConstructor().newInstance()).load();
        }
        assertEquals(List.of("constructor"),
            List.of(definition.getConstructor().getAnnotationMetadata().stringValues(Enhanced.class)));
    }

    /**
     * Writes on each constructor and method of the engine whether it is one or the other.
     */
    public static final class MarksEveryMethod implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = true)
        public void mark(MethodConfig method) {
            if (method.info().declaringClass().name().equals("engines.Engine")) {
                method.addAnnotation(AnnotationBuilder.of(Enhanced.class)
                    .value(method.info().isConstructor() ? "constructor" : "method").build());
            }
        }
    }
}
