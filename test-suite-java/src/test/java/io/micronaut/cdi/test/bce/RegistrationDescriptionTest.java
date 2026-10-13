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

    @Test
    void aBeanIsDescribedByWhatItDeclaresAndToTheRegistrationsItsTypesAndKindMatch() {
        Selective extension = new Selective();
        BuildCompatibleExtensionVisitor.overrideExtensions(List.of(extension));
        try (JavaParser parser = new JavaParser()) {
            parser.generate("selective.Subject", """
                package selective;

                public class Subject {

                    public interface View<T> {
                    }

                    public static class Box<T> implements View<T> {
                    }

                    public static class Gadget {
                    }

                    public static class Widget {
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Circle implements io.micronaut.cdi.test.bce.RegistrationSubjects.Shape {
                    }

                    @jakarta.enterprise.context.Dependent
                    @jakarta.enterprise.inject.Typed(Square.class)
                    public static class Square implements io.micronaut.cdi.test.bce.RegistrationSubjects.Shape {
                    }

                    @jakarta.enterprise.context.Dependent
                    @jakarta.inject.Named("factory")
                    @jakarta.enterprise.inject.Typed(Factory.class)
                    public static class Factory {
                        @jakarta.enterprise.inject.Produces
                        Gadget gadget() {
                            return new Gadget();
                        }

                        @SuppressWarnings("rawtypes")
                        @jakarta.enterprise.inject.Produces
                        Box rawBox() {
                            return new Box();
                        }
                    }

                    @jakarta.enterprise.inject.Alternative
                    @jakarta.annotation.Priority(3)
                    @jakarta.enterprise.context.Dependent
                    public static class AlternativeFactory {
                        @jakarta.enterprise.inject.Produces
                        Widget widget = new Widget();
                    }

                    @jakarta.interceptor.InterceptorBinding
                    @java.lang.annotation.Repeatable(Tags.class)
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                    public @interface Tag {
                        String value();
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                    public @interface Tags {
                        Tag[] value();
                    }

                    @jakarta.interceptor.Interceptor
                    @jakarta.annotation.Priority(1)
                    @Tag("a")
                    @Tag("b")
                    public static class Tagging {
                        @jakarta.interceptor.AroundInvoke
                        Object around(jakarta.interceptor.InvocationContext context) throws Exception {
                            return context.proceed();
                        }
                    }

                    public static class GenericListener<T> {
                        void on(@jakarta.enterprise.event.Observes T event) {
                        }
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class PingListener extends GenericListener<
                        io.micronaut.cdi.test.bce.RegistrationSubjects.Ping> {
                    }
                }
                """);
        }
        // narrowed by Typed, a bean is not one of the types it leaves out
        assertEquals(List.of("Circle"), extension.shapes);
        // and only an interceptor is described as one
        assertEquals(List.of("Tagging [a, b]"), extension.interceptors);
        // a producer is read off what the method declares, not what its class does
        assertEquals("null [selective.Subject$Gadget, java.lang.Object]", extension.producers.get("gadget"));
        // the supertypes of a raw type are raw
        assertEquals("null [selective.Subject$Box, selective.Subject$View, java.lang.Object]",
            extension.producers.get("rawBox"));
        // a producer field of an alternative class is an alternative
        assertEquals("alternative", extension.producers.get("widget"));
        // the type an inherited observer observes is the one the subclass binds
        assertEquals(List.of("Ping"), extension.pings);
    }

    public static class Selective implements BuildCompatibleExtension {
        final List<String> shapes = new ArrayList<>();
        final List<String> interceptors = new ArrayList<>();
        final Map<String, String> producers = new LinkedHashMap<>();
        final List<String> pings = new ArrayList<>();

        @Registration(types = Object.class)
        public void shapesOrNot(BeanInfo bean) {
            if (!bean.declaringClass().name().startsWith("selective.")) {
                return;
            }
            if (bean.isProducerMethod()) {
                List<String> typeNames = new ArrayList<>();
                for (Type type : bean.types()) {
                    typeNames.add(type.isClass() ? type.asClass().declaration().name() : type.toString());
                }
                producers.put(bean.producerMethod().name(), bean.name() + " " + typeNames);
            } else if (bean.isProducerField()) {
                producers.put(bean.producerField().name(), bean.isAlternative() ? "alternative" : "not");
            }
        }

        @Registration(types = RegistrationSubjects.Shape.class)
        public void shape(BeanInfo bean) {
            if (bean.declaringClass().name().startsWith("selective.")) {
                shapes.add(simple(bean.declaringClass().name()));
            }
        }

        @Registration(types = Object.class)
        public void interceptor(jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo interceptor) {
            if (!interceptor.declaringClass().name().startsWith("selective.")) {
                return;
            }
            List<String> values = new ArrayList<>();
            for (AnnotationInfo binding : interceptor.interceptorBindings()) {
                values.add(binding.value().asString());
            }
            java.util.Collections.sort(values);
            interceptors.add(simple(interceptor.declaringClass().name()) + " " + values);
        }

        @Registration(types = RegistrationSubjects.Ping.class)
        public void observer(ObserverInfo observer) {
            if (observer.declaringClass().name().endsWith("$PingListener") && observer.eventType().isClass()) {
                pings.add(simple(observer.eventType().asClass().declaration().name()));
            }
        }

        private static String simple(String name) {
            return name.substring(name.lastIndexOf('$') + 1);
        }
    }

    @Test
    void aClassOfAVetoedPackageIsNoBeanToDescribe() {
        Scanning extension = new Scanning(false);
        BuildCompatibleExtensionVisitor.overrideExtensions(List.of(extension));
        try (JavaParser parser = new JavaParser()) {
            parser.generate(
                source("vetoedpkg.package-info", """
                    @jakarta.enterprise.inject.Vetoed
                    package vetoedpkg;
                    """),
                source("vetoedpkg.Hidden", """
                    package vetoedpkg;

                    @jakarta.enterprise.context.Dependent
                    public class Hidden {
                    }
                    """));
        }
        assertEquals(List.of(), extension.described);
    }

    @Test
    void aClassTheDiscoveryPhaseScannedIsDescribedAsTheDependentBeanItIs() {
        Scanning extension = new Scanning(true);
        BuildCompatibleExtensionVisitor.overrideExtensions(List.of(extension));
        try (JavaParser parser = new JavaParser()) {
            parser.generate("scanned.Plain", """
                package scanned;

                public class Plain {
                }
                """);
        }
        assertEquals(List.of("scanned.Plain jakarta.enterprise.context.Dependent"), extension.described);
    }

    private static javax.tools.JavaFileObject source(String className, String code) {
        return new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///"
            + className.replace('.', '/') + javax.tools.JavaFileObject.Kind.SOURCE.extension),
            javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    public static class Scanning implements BuildCompatibleExtension {
        final List<String> described = new ArrayList<>();
        private final boolean scans;

        Scanning(boolean scans) {
            this.scans = scans;
        }

        @jakarta.enterprise.inject.build.compatible.spi.Discovery
        public void scan(jakarta.enterprise.inject.build.compatible.spi.ScannedClasses scanned) {
            if (scans) {
                scanned.add("scanned.Plain");
            }
        }

        @Registration(types = Object.class)
        public void bean(BeanInfo bean) {
            String name = bean.declaringClass().name();
            if (name.startsWith("vetoedpkg.") || name.startsWith("scanned.")) {
                described.add(name + " " + bean.scope().name());
            }
        }
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
