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
                public record Person(@Person.ComponentOnly String name, int age) {

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.RECORD_COMPONENT)
                    public @interface ComponentOnly {
                    }
                }
                """);
        }
        assertEquals(List.of("name String field name accessor name annotated true",
            "age int field age accessor age annotated false"), Components.read);
    }

    public static class Components implements BuildCompatibleExtension {
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
                    + component.accessor().name() + " annotated "
                    + component.hasAnnotation(a -> a.name().equals("records.Person$ComponentOnly")));
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
}
