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
package io.micronaut.cdi.test.disposer;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer disposes of what a producer of its class produces by the rules of typesafe resolution (section 3.4.3):
 * the qualifiers of its disposed parameter are required of the producer, which may have more, an {@code Any} among
 * them requires nothing more of it than the others do, and a member is compared by its value, an array by its
 * elements and one left at its default as the default written down.
 */
class DisposerQualifierMatchingTest {

    /**
     * What was disposed of, held by the container.
     */
    @jakarta.inject.Singleton
    public static class Disposals {
        private final List<String> disposed = new java.util.concurrent.CopyOnWriteArrayList<>();

        void add(String what) {
            disposed.add(what);
        }

        List<String> disposed() {
            return List.copyOf(disposed);
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Red {
        final class Literal extends AnnotationLiteral<Red> implements Red {
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Blue {
        final class Literal extends AnnotationLiteral<Blue> implements Blue {
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Big {
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tags {
        String[] value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Size {
        int value() default 1;
    }

    record Shape(String name) {
    }

    record Paint(String name) {
    }

    record Label(String name) {
    }

    record Crate(String name) {
    }

    @Dependent
    public static class ShapeMaker {
        @jakarta.inject.Inject
        Disposals disposals;

        @Produces
        @Red
        @Big
        Shape shape() {
            return new Shape("big red shape");
        }

        void dispose(@Disposes @Red Shape shape) {
            disposals.add(shape.name());
        }
    }

    @Dependent
    public static class PaintMaker {
        @jakarta.inject.Inject
        Disposals disposals;

        @Produces
        @Red
        Paint red() {
            return new Paint("red paint");
        }

        @Produces
        @Blue
        Paint blue() {
            return new Paint("blue paint");
        }

        void dispose(@Disposes @Any @Red Paint paint) {
            disposals.add(paint.name());
        }
    }

    @Dependent
    public static class LabelMaker {
        @jakarta.inject.Inject
        Disposals disposals;

        @Produces
        @Tags({"x", "y"})
        Label label() {
            return new Label("tagged label");
        }

        void dispose(@Disposes @Tags({"x", "y"}) Label label) {
            disposals.add(label.name());
        }
    }

    @Dependent
    public static class CrateMaker {
        @jakarta.inject.Inject
        Disposals disposals;

        @Produces
        @Size
        Crate crate() {
            return new Crate("sized crate");
        }

        void dispose(@Disposes @Size(1) Crate crate) {
            disposals.add(crate.name());
        }
    }

    private static <T> void createAndDestroy(Class<T> type, Annotation qualifier) {
        Instance.Handle<T> handle = CDI.current().select(type, qualifier).getHandle();
        handle.get();
        handle.destroy();
    }

    @Test
    void theDisposedParameterRequiresItsQualifiersOfTheProducer() {
        List<String> disposed;
        try (ApplicationContext context = ApplicationContext.run()) {
            createAndDestroy(Shape.class, new Red.Literal());
            createAndDestroy(Paint.class, new Red.Literal());
            createAndDestroy(Paint.class, new Blue.Literal());
            createAndDestroy(Label.class, Any.Literal.INSTANCE);
            createAndDestroy(Crate.class, Any.Literal.INSTANCE);
            disposed = context.getBean(Disposals.class).disposed();
        }
        assertEquals(List.of("big red shape", "red paint", "tagged label", "sized crate"), disposed);
    }
}
