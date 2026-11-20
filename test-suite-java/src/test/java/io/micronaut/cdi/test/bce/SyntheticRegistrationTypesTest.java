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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A synthetic bean is described to a registration method by the bean types its builder declared, as a class bean is by
 * its bean types (CDI 4.1 section 2.10.3), not by the class it is created as.
 */
class SyntheticRegistrationTypesTest {

    static final List<String> TOLD = new CopyOnWriteArrayList<>();

    private static final String SOURCE = """
        package registered;

        public class Anchor {
        }
        """;

    @Test
    void aSyntheticBeanIsMatchedByItsDeclaredBeanTypes() throws Exception {
        TOLD.clear();
        try (JavaParser parser = ExtensionCompilation.of(Registering.class)) {
            parser.generate("registered.Anchor", SOURCE);
        }
        assertEquals(List.of("by Object", "by its bean type"), TOLD.stream().sorted().toList());
    }

    /**
     * The bean type the synthetic bean declares.
     */
    public interface Declared {
    }

    /**
     * Another type of the implementation class, which the bean does not declare.
     */
    public interface Undeclared {
    }

    /**
     * The class the synthetic bean is created as.
     */
    public static final class Implementation implements Declared, Undeclared {
    }

    /**
     * Adds the synthetic bean and records which registration methods are told about it.
     */
    public static final class Registering implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Implementation.class)
                .type(Declared.class)
                .scope(Dependent.class)
                .createWith(ImplementationCreator.class);
        }

        @Registration(types = Declared.class)
        public void byDeclared(BeanInfo bean) {
            if (bean.isSynthetic()) {
                TOLD.add("by its bean type");
            }
        }

        @Registration(types = Undeclared.class)
        public void byUndeclared(BeanInfo bean) {
            if (bean.isSynthetic()) {
                TOLD.add("by a type of its class it does not declare");
            }
        }

        @Registration(types = Implementation.class)
        public void byImplementation(BeanInfo bean) {
            if (bean.isSynthetic()) {
                TOLD.add("by its class");
            }
        }

        @Registration(types = Object.class)
        public void byObject(BeanInfo bean) {
            if (bean.isSynthetic()) {
                TOLD.add("by Object");
            }
        }
    }

    /**
     * Creates the synthetic bean.
     */
    public static final class ImplementationCreator implements SyntheticBeanCreator<Implementation> {
        @Override
        public Implementation create(SyntheticInjections injections, Parameters params) {
            return new Implementation();
        }
    }
}
