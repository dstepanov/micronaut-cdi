
package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.ModelProbe;
import io.micronaut.cdi.test.extension.ModelReport;
import io.micronaut.cdi.test.extension.TypeMarker;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language model a build compatible extension reads a class through, held to what the kit's language model
 * part verifies, on the things this implementation once got wrong: only the annotations retained at runtime; the
 * kind of a nested class, an enum with abstract methods being abstract; the annotations written on a use of a
 * type, on a superclass, a receiver and a thrown type; the type parameters a class declares; a constructor
 * returning the class it constructs; inherited members listed and a bridge method not; the default of an
 * annotation member; and two readings of one thing comparing equal.
 *
 * <p>The extension runs as {@link ModelFixture} compiles and writes what it read onto the class; the test reads
 * it back from the bean definition.</p>
 */
class LanguageModelTest {

    @Retention(RetentionPolicy.SOURCE)
    @interface SourceMarker {
    }

    @Retention(RetentionPolicy.CLASS)
    @interface ClassMarker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface RuntimeMarker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag {
        String value() default "untagged";
    }

    enum Shape {
        CIRCLE {
            @Override
            double area() {
                return Math.PI;
            }
        };

        abstract double area();
    }

    record Point(int x) {
    }

    static class ModelBase<S> {
        S base;

        void inherited() {
        }
    }

    @Dependent
    @ModelProbe
    @SourceMarker
    @ClassMarker
    @RuntimeMarker
    static class ModelFixture<T extends Number> extends @TypeMarker ModelBase<@TypeMarker String>
        implements Comparable<ModelFixture<T>> {

        @TypeMarker List<@TypeMarker String> tagged;
        @TypeMarker List<@TypeMarker String> taggedAgain;
        int first;
        int second;
        ModelFixture<T> self;
        Shape shape;
        Point point;
        Tag tag;
        @Tag String defaulted;

        void receiving(@TypeMarker ModelFixture<T> this) {
        }

        void throwing() throws @TypeMarker IOException {
        }

        @Override
        public int compareTo(ModelFixture<T> other) {
            return 0;
        }
    }

    @Test
    void theModelReportsOnlyTheAnnotationsRetainedAtRuntime() {
        List<String> annotations = Arrays.asList(report().stringValue(ModelReport.class, "annotations")
            .orElseThrow().split(","));
        assertTrue(annotations.contains("RuntimeMarker"), annotations.toString());
        assertTrue(annotations.contains("ModelProbe"), annotations.toString());
        assertFalse(annotations.contains("SourceMarker"), annotations.toString());
        assertFalse(annotations.contains("ClassMarker"), annotations.toString());
    }

    @Test
    void theModelKnowsTheKindOfAClass() {
        // an annotation interface is abstract, as every interface is
        assertEquals("shape:enum,abstract;point:record;tag:annotation,abstract",
            report().stringValue(ModelReport.class, "kinds").orElseThrow());
    }

    @Test
    void theModelCarriesTheAnnotationsOfAUseOfAType() {
        AnnotationMetadata report = report();
        assertEquals("annotated,argument-annotated", report.stringValue(ModelReport.class, "fieldType").orElseThrow());
        assertEquals("annotated,argument-annotated", report.stringValue(ModelReport.class, "superType").orElseThrow());
        assertEquals("receiver,throws", report.stringValue(ModelReport.class, "receiverAndThrows").orElseThrow());
    }

    @Test
    void theModelDeclaresTheTypeParametersAndTheConstructorsOfAClass() {
        AnnotationMetadata report = report();
        assertEquals("T extends java.lang.Number", report.stringValue(ModelReport.class, "typeParameters").orElseThrow());
        assertEquals(ModelFixture.class.getName(), report.stringValue(ModelReport.class, "constructorReturn").orElseThrow());
    }

    @Test
    void theModelListsInheritedMembersAndNoBridgeMethod() {
        List<String> members = Arrays.asList(report().stringValue(ModelReport.class, "members").orElseThrow().split(","));
        assertTrue(members.contains("inherited"), members.toString());
        assertTrue(members.contains("base"), members.toString());
        assertTrue(members.contains("receiving"), members.toString());
        // the class's own compareTo and the one it inherits from Comparable, which the model lists both of; the
        // bridge the compiler writes for the override would make a third
        assertEquals(2, members.stream().filter("compareTo"::equals).count(), members.toString());
    }

    @Test
    void theModelFillsInTheDefaultOfAnAnnotationMember() {
        assertEquals("untagged", report().stringValue(ModelReport.class, "defaults").orElseThrow());
    }

    @Test
    void twoReadingsOfOneThingAreEqual() {
        assertEquals("primitives,declarations,parameterized", report().stringValue(ModelReport.class, "equality").orElseThrow());
    }

    private static AnnotationMetadata report() {
        try (ApplicationContext context = ApplicationContext.run()) {
            return context.getBeanDefinition(ModelFixture.class).getAnnotationMetadata();
        }
    }
}
