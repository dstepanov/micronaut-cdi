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
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration phase of section 2.10.3 is told of the beans and the observers the compilation declares as the
 * container resolves them: a class bean only where the class is one, a producer by the class that declares it,
 * the bean types and the qualifiers a lookup resolves the bean by, and the priority an observer is notified in.
 */
class RegistrationDescriptionTest {

    @AfterEach
    void restoreServiceLoading() {
        BuildCompatibleExtensionVisitor.overrideExtensions(null);
    }

    @Test
    void theBeansAndObserversAreDescribedAsTheContainerResolvesThem() {
        Recording extension = new Recording();
        BuildCompatibleExtensionVisitor.overrideExtensions(List.of(extension));
        try (JavaParser parser = new JavaParser()) {
            parser.generate("registration.Subject", """
                package registration;

                public class Subject {

                    public interface Shape {
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Parent {
                        @jakarta.enterprise.inject.Produces
                        StringBuilder parentWord() {
                            return new StringBuilder();
                        }
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Child extends Parent implements Shape {
                    }

                    @jakarta.enterprise.context.Dependent
                    @jakarta.enterprise.inject.Typed(Square.class)
                    public static class Square implements Shape {
                    }

                    @jakarta.enterprise.inject.Vetoed
                    @jakarta.enterprise.context.Dependent
                    public static class Refused {
                    }

                    @jakarta.enterprise.context.Dependent
                    public abstract static class Unfinished {
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Unbuildable {
                        public Unbuildable(String argument) {
                        }
                    }

                    @jakarta.enterprise.inject.Stereotype
                    @jakarta.enterprise.inject.Alternative
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Mock {
                    }

                    @Mock
                    @jakarta.annotation.Priority(5)
                    @jakarta.enterprise.context.Dependent
                    public static class Stub {
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Listener {
                        @jakarta.annotation.Priority(7)
                        void on(@jakarta.enterprise.event.Observes Child child) {
                        }
                    }
                }
                """);
        }
        Map<String, String> beans = extension.beans;
        // the class beans, and the producer once, by the class that declares it
        assertEquals(Set.of("Parent", "Child", "Square", "Stub", "Listener", "Parent#parentWord"),
            beans.keySet());
        assertEquals("[registration.Subject$Child, registration.Subject$Shape, registration.Subject$Parent, "
            + "java.lang.Object]", extension.types.get("Child"));
        assertEquals("[registration.Subject$Square, java.lang.Object]", extension.types.get("Square"));
        assertEquals("[jakarta.enterprise.inject.Any, jakarta.enterprise.inject.Default]",
            extension.qualifiers.get("Child"));
        assertEquals("alternative 5", beans.get("Stub"));
        assertEquals(List.of("Child 7"), extension.observers);
        assertTrue(extension.qualifiers.get("Parent#parentWord").contains("Default"));
        assertFalse(extension.qualifiers.get("Parent#parentWord").contains("Primary"));
    }

    public static class Recording implements BuildCompatibleExtension {
        final Map<String, String> beans = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();
        final Map<String, String> qualifiers = new LinkedHashMap<>();
        final List<String> observers = new ArrayList<>();

        @Registration(types = Object.class)
        public void bean(BeanInfo bean) {
            String declaring = simple(bean.declaringClass().name());
            String key = bean.isClassBean() ? declaring
                : declaring + "#" + (bean.isProducerMethod() ? bean.producerMethod().name() : bean.producerField().name());
            if (!key.startsWith("Parent") && !key.startsWith("Child") && !key.startsWith("Square")
                && !key.startsWith("Refused") && !key.startsWith("Unfinished") && !key.startsWith("Unbuildable")
                && !key.startsWith("Stub") && !key.startsWith("Listener")) {
                return;
            }
            String previous = beans.put(key, (bean.isAlternative() ? "alternative " : "") + bean.priority());
            assertEquals(null, previous, key + " described twice");
            List<String> typeNames = new ArrayList<>();
            for (Type type : bean.types()) {
                typeNames.add(type.isClass() ? type.asClass().declaration().name() : type.toString());
            }
            types.put(key, typeNames.toString());
            Set<String> qualifierNames = new TreeSet<>();
            for (AnnotationInfo qualifier : bean.qualifiers()) {
                qualifierNames.add(qualifier.name());
            }
            qualifiers.put(key, qualifierNames.toString());
        }

        @Registration(types = Object.class)
        public void observer(ObserverInfo observer) {
            if (observer.declaringClass().name().endsWith("$Listener")) {
                observers.add(simple(observer.eventType().asClass().declaration().name()) + " " + observer.priority());
            }
        }

        private static String simple(String name) {
            return name.substring(name.lastIndexOf('$') + 1);
        }
    }
}
