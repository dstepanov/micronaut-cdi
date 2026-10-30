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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language model an extension reads Java source through, on what review 8 found it answering wrongly: the
 * components of a record.
 */
class LanguageModelReviewTest {

    @Test
    void aRecordReportsItsComponentsWithTheirAnnotations() {
        Components.read.clear();
        try (JavaParser parser = ExtensionCompilation.of(Components.class)) {
            parser.generate("records.Person", """
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
                """);
        }
        assertEquals(List.of("name String field name accessor name annotated [ComponentOnly]"
                + " field annotated [FieldOnly] accessor annotated [AccessorOnly]",
            "age int field age accessor age annotated [] field annotated [] accessor annotated []"), Components.read);
    }

    public static class Components implements BuildCompatibleExtension {
        private static List<String> simpleNames(java.util.Collection<jakarta.enterprise.lang.model.AnnotationInfo> annotations) {
            return annotations.stream().map(a -> a.name().substring(a.name().lastIndexOf('$') + 1)).sorted().toList();
        }

        static final List<String> read = new ArrayList<>();

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("records.Person")) {
                return;
            }
            assertTrue(info.isRecord());
            for (RecordComponentInfo component : info.recordComponents()) {
                String type = component.type().isClass()
                    ? component.type().asClass().declaration().simpleName() : component.type().toString();
                read.add(component.name() + " " + type + " field " + component.field().name() + " accessor "
                    + component.accessor().name() + " annotated " + simpleNames(component.annotations())
                    + " field annotated " + simpleNames(component.field().annotations())
                    + " accessor annotated " + simpleNames(component.accessor().annotations()));
            }
        }
    }

    @Test
    void aClassLiteralOfAGenericClassIsAClassType() {
        Literals.read.clear();
        try (JavaParser parser = ExtensionCompilation.of(Literals.class)) {
            parser.generate("literals.Holder", """
                package literals;

                @Holder.Names({java.util.List.class, java.util.Map[].class})
                @jakarta.enterprise.context.Dependent
                public class Holder {

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Names {
                        Class<?>[] value();
                    }
                }
                """);
        }
        assertEquals(List.of("CLASS java.util.List", "ARRAY java.util.Map"), Literals.read);
    }

    public static class Literals implements BuildCompatibleExtension {
        static final List<String> read = new ArrayList<>();

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("literals.Holder")) {
                return;
            }
            AnnotationInfo names = info.annotations(a -> a.name().equals("literals.Holder$Names")).iterator().next();
            for (AnnotationMember member : names.value().asArray()) {
                Type type = member.asType();
                Type named = type.isArray() ? type.asArray().componentType() : type;
                read.add(type.kind() + " " + named.asClass().declaration().name());
            }
        }
    }

    @Test
    void theCollectionsOfTheModelAreImmutable() {
        Collections.mutable.clear();
        Collections.checked = false;
        try (JavaParser parser = ExtensionCompilation.of(Collections.class)) {
            parser.generate("collections.Subject", """
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
                """);
        }
        assertTrue(Collections.checked, "the extension ran");
        assertEquals(List.of(), Collections.mutable);
    }

    public static class Collections implements BuildCompatibleExtension {
        static final List<String> mutable = new ArrayList<>();
        static volatile boolean checked;

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            ClassInfo info = config.info();
            if (!info.name().equals("collections.Subject")) {
                return;
            }
            AnnotationInfo tags = info.annotations(a -> a.name().equals("collections.Subject$Tags")).iterator().next();
            MethodInfo method = info.methods().stream().filter(m -> m.name().equals("method")).findFirst().orElseThrow();
            Type fieldType = info.fields().stream().filter(f -> f.name().equals("field")).findFirst().orElseThrow()
                .type();
            tryClear("constructors", info.constructors());
            tryClear("methods", info.methods());
            tryClear("fields", info.fields());
            tryClear("parameters", method.parameters());
            tryClear("members", tags.members().values());
            tryClear("asArray", tags.value().asArray());
            tryClear("type annotations", fieldType.annotations());
            tryClear("class annotations", info.annotations());
            tryClear("repeatable annotation", info.repeatableAnnotation(jakarta.enterprise.context.Dependent.class));
            tryClear("super interfaces", info.superInterfaces());
            tryClear("class type parameters", info.typeParameters());
            tryClear("method type parameters", method.typeParameters());
            tryClear("thrown types", method.throwsTypes());
            tryClear("type repeatable annotation", fieldType.repeatableAnnotation(jakarta.enterprise.context.Dependent.class));
            checked = true;
        }

        private static void tryClear(String what, java.util.Collection<?> collection) {
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
        Receivers.read.clear();
        try (JavaParser parser = ExtensionCompilation.of(Receivers.class)) {
            parser.generate("receivers.Outer", """
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
                """);
        }
        assertEquals(List.of("constructor receivers.Outer", "method receivers.Outer$Inner"), Receivers.read);
    }

    public static class Receivers implements BuildCompatibleExtension {
        static final List<String> read = new ArrayList<>();

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config) {
            if (!config.info().name().equals("receivers.Outer")) {
                return;
            }
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
        }
    }
}
