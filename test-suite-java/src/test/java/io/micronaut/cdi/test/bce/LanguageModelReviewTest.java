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
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language model an extension reads Java source through, on what review 8 found it answering wrongly: the
 * components of a record, class literals, mutable collections and the receiver of an inner class constructor.
 */
class LanguageModelReviewTest {

    /**
     * What an extension read, which it writes onto the class it read, and the test reads back from the bean definition
     * the compilation wrote: nothing is kept beside the compilation.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Report {
        String[] value();
    }

    private static void report(ClassConfig config, List<String> read) {
        config.addAnnotation(AnnotationBuilder.of(Report.class).value(read.toArray(new String[0])).build());
    }

    private static List<String> reported(Iterable<? extends JavaFileObject> files, String definition) {
        ClassLoader loader = CompiledClasses.of(files).loader(LanguageModelReviewTest.class.getClassLoader());
        try {
            BeanDefinitionReference<?> reference = (BeanDefinitionReference<?>) loader.loadClass(definition)
                .getDeclaredConstructor().newInstance();
            return List.of(reference.getAnnotationMetadata().stringValues(Report.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRecordReportsItsComponentsWithTheirAnnotations() {
        List<String> read;
        try (JavaParser parser = ExtensionCompilation.of(Components.class)) {
            read = reported(parser.generate("records.Person", """
                package records;

                @jakarta.enterprise.context.Dependent
                public record Person(@Person.ComponentOnly @Person.FieldOnly String name, int age) {

                    @Person.AccessorOnly
                    public String name() {
                        return name;
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
                    public @interface FieldOnly {
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD,
                        java.lang.annotation.ElementType.RECORD_COMPONENT})
                    public @interface AccessorOnly {
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.RECORD_COMPONENT)
                    public @interface ComponentOnly {
                    }
                }
                """), "records.$Person$Definition");
        }
        assertEquals(List.of("name String field name accessor name annotated [ComponentOnly]"
                + " field annotated [FieldOnly] accessor annotated [AccessorOnly]",
            "age int field age accessor age annotated [] field annotated [] accessor annotated []"), read);
    }

    public static class Components implements BuildCompatibleExtension {
        private static List<String> simpleNames(java.util.Collection<jakarta.enterprise.lang.model.AnnotationInfo> annotations) {
            return annotations.stream().map(a -> a.name().substring(a.name().lastIndexOf('$') + 1)).sorted().toList();
        }

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("records.Person")) {
                return;
            }
            List<String> read = new ArrayList<>();
            assertTrue(info.isRecord());
            for (RecordComponentInfo component : info.recordComponents()) {
                String type = component.type().isClass()
                    ? component.type().asClass().declaration().simpleName() : component.type().toString();
                read.add(component.name() + " " + type + " field " + component.field().name() + " accessor "
                    + component.accessor().name() + " annotated " + simpleNames(component.annotations())
                    + " field annotated " + simpleNames(component.field().annotations())
                    + " accessor annotated " + simpleNames(component.accessor().annotations()));
            }
            report(config, read);
        }
    }

    @Test
    void aClassLiteralOfAGenericClassIsAClassType() {
        List<String> read;
        try (JavaParser parser = ExtensionCompilation.of(Literals.class)) {
            read = reported(parser.generate("literals.Holder", """
                package literals;

                @Holder.Names({java.util.List.class, java.util.Map[].class})
                @jakarta.enterprise.context.Dependent
                public class Holder {

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Names {
                        Class<?>[] value();
                    }
                }
                """), "literals.$Holder$Definition");
        }
        assertEquals(List.of("CLASS java.util.List", "ARRAY java.util.Map"), read);
    }

    public static class Literals implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("literals.Holder")) {
                return;
            }
            List<String> read = new ArrayList<>();
            AnnotationInfo names = info.annotations(a -> a.name().equals("literals.Holder$Names")).iterator().next();
            for (AnnotationMember member : names.value().asArray()) {
                Type type = member.asType();
                Type named = type.isArray() ? type.asArray().componentType() : type;
                read.add(type.kind() + " " + named.asClass().declaration().name());
            }
            report(config, read);
        }
    }

    @Test
    void theCollectionsOfTheModelAreImmutable() {
        List<String> read;
        try (JavaParser parser = ExtensionCompilation.of(Collections.class)) {
            read = reported(parser.generate("collections.Subject", """
                package collections;

                @Subject.Tags({"a", "b"})
                @Subject.Mark("x")
                @Subject.Mark("y")
                @jakarta.enterprise.context.Dependent
                public class Subject<T> implements java.io.Serializable {

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Repeatable(Marks.class)
                    public @interface Mark {
                        String value();
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Marks {
                        Mark[] value();
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                    public @interface Used {
                    }

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Tags {
                        String[] value();
                    }

                    @Used String field;

                    <M> void method(String parameter) throws java.io.IOException {
                    }
                }
                """), "collections.$Subject$Definition");
        }
        assertEquals(List.of("checked"), read, "the extension ran, and found every collection immutable");
    }

    public static class Collections implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("collections.Subject")) {
                return;
            }
            List<String> read = new ArrayList<>(List.of("checked"));
            AnnotationInfo tags = info.annotations(a -> a.name().equals("collections.Subject$Tags")).iterator().next();
            MethodInfo method = info.methods().stream().filter(m -> m.name().equals("method")).findFirst().orElseThrow();
            Type fieldType = info.fields().stream().filter(f -> f.name().equals("field")).findFirst().orElseThrow()
                .type();
            tryClear(read, "constructors", info.constructors());
            tryClear(read, "methods", info.methods());
            tryClear(read, "fields", info.fields());
            tryClear(read, "parameters", method.parameters());
            tryClear(read, "members", tags.members().values());
            tryClear(read, "asArray", tags.value().asArray());
            tryClear(read, "type annotations", fieldType.annotations());
            tryClear(read, "class annotations", info.annotations());
            tryClear(read, "repeatable annotation", info.repeatableAnnotation(jakarta.enterprise.context.Dependent.class));
            tryClear(read, "super interfaces", info.superInterfaces());
            tryClear(read, "class type parameters", info.typeParameters());
            tryClear(read, "method type parameters", method.typeParameters());
            tryClear(read, "thrown types", method.throwsTypes());
            tryClear(read, "type repeatable annotation", fieldType.repeatableAnnotation(jakarta.enterprise.context.Dependent.class));
            report(config, read);
        }

        private static void tryClear(List<String> mutable, String what, java.util.Collection<?> collection) {
            try {
                collection.clear();
                mutable.add(what);
            } catch (UnsupportedOperationException e) {
                // immutable, as the model is
            }
        }
    }

    @Test
    void theReceiverOfAnInnerClassConstructorIsTheEnclosingClass() {
        List<String> read;
        try (JavaParser parser = ExtensionCompilation.of(Receivers.class)) {
            read = reported(parser.generate("receivers.Outer", """
                package receivers;

                @jakarta.enterprise.context.Dependent
                public class Outer {

                    public Inner make() {
                        return new Inner();
                    }

                    public class Inner {
                        public Inner() {
                        }

                        public void method() {
                        }
                    }
                }
                """), "receivers.$Outer$Definition");
        }
        assertEquals(List.of("constructor receivers.Outer", "method receivers.Outer$Inner"), read);
    }

    public static class Receivers implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            if (!config.info().name().equals("receivers.Outer")) {
                return;
            }
            List<String> read = new ArrayList<>();
            // the inner class, as the method that makes one names it
            ClassInfo info = config.info().methods().stream().filter(m -> m.name().equals("make")).findFirst()
                .orElseThrow().returnType().asClass().declaration();
            for (MethodInfo constructor : info.constructors()) {
                read.add("constructor " + constructor.receiverType().asClass().declaration().name());
            }
            for (MethodInfo method : info.methods()) {
                if (method.name().equals("method")) {
                    read.add("method " + method.receiverType().asClass().declaration().name());
                }
            }
            report(config, read);
        }
    }
}
