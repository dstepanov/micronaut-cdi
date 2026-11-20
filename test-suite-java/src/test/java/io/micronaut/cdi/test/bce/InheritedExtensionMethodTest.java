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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The extension methods of a build compatible extension include the ones it inherits from a superclass, and a method
 * it overrides runs once, as the override.
 */
class InheritedExtensionMethodTest {

    static final List<String> RAN = new CopyOnWriteArrayList<>();

    private static final String SOURCE = """
        package inherited;

        @jakarta.enterprise.context.Dependent
        public class Target {
        }
        """;

    @Test
    void anExtensionMethodASuperclassDeclaresRuns() throws Exception {
        RAN.clear();
        try (JavaParser parser = ExtensionCompilation.of(Inheriting.class)) {
            parser.generate("inherited.Target", SOURCE);
        }
        assertEquals(List.of("base inherited", "overriding", "own"), RAN.stream().sorted().toList());
    }

    /**
     * Declares one registration method its subclass inherits and one it overrides.
     */
    public abstract static class Base implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void inherited(BeanInfo bean) {
            if (isTarget(bean)) {
                RAN.add("base inherited");
            }
        }

        @Registration(types = Object.class)
        public void overridden(BeanInfo bean) {
            if (isTarget(bean)) {
                RAN.add("base overridden");
            }
        }

        static boolean isTarget(BeanInfo bean) {
            return bean.isClassBean() && bean.declaringClass().name().equals("inherited.Target");
        }
    }

    /**
     * The extension.
     */
    public static final class Inheriting extends Base {
        @Registration(types = Object.class)
        public void own(BeanInfo bean) {
            if (isTarget(bean)) {
                RAN.add("own");
            }
        }

        @Override
        @Registration(types = Object.class)
        public void overridden(BeanInfo bean) {
            if (isTarget(bean)) {
                RAN.add("overriding");
            }
        }
    }
}
