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
package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.cdi.internal.runtime.CdiQualifier;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.interceptor.spi.InterceptorBindings;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation compared as a qualifier is compared as the Jakarta Interceptors implementation compares it as an
 * interceptor binding: the container's answer for a qualifier, its answer for an interceptor binding and the
 * answer of Micronaut's {@link AnnotationValue#matches} and of {@link InterceptorBindings} are one, for the values a
 * program composes and
 * for the literals it hands over.
 */
class QualifierAsInterceptorBindingTest {

    enum Level {
        LOW, HIGH
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Origin {
        String city() default "";
    }

    @Qualifier
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @interface Audited {

        Level level() default Level.LOW;

        String region() default "";

        @Nonbinding String note() default "";
    }

    static final class AuditedLiteral extends AnnotationLiteral<Audited> implements Audited {

        private final Level level;
        private final String region;
        private final String note;

        AuditedLiteral(Level level, String region, String note) {
            this.level = level;
            this.region = region;
            this.note = note;
        }

        @Override
        public Level level() {
            return level;
        }

        @Override
        public String region() {
            return region;
        }

        @Override
        public String note() {
            return note;
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Shelf {
        String value() default "top";
    }

    interface Book {
    }

    @Shelf
    @Dependent
    static class TopBook implements Book {
    }

    @Shelf("bottom")
    @Dependent
    static class BottomBook implements Book {
    }

    private static AnnotationValue<Audited> audited(Object level, String region, String note) {
        return AnnotationValue.builder(Audited.class)
            .members(java.util.Map.of("level", level))
            .member("region", region)
            .member("note", note)
            .member("$nonBinding", "note")
            .build();
    }

    private static void assertComparedAlike(AnnotationValue<?> one, AnnotationValue<?> other, boolean expected) {
        assertEquals(expected, CdiQualifier.ofValue(null, one).matches(CdiQualifier.ofValue(null, other)), one + " and " + other);
        assertEquals(expected, InterceptorBindings.ofValues(List.of(one)).equals(InterceptorBindings.ofValues(
            List.of(other))), one + " and " + other);
        // what the container hands Micronaut and the interceptors implementation is compared there as the container
        // compares it
        AnnotationValue<?> written = CdiQualifier.ofValue(null, one).written();
        AnnotationValue<?> otherWritten = CdiQualifier.ofValue(null, other).written();
        assertEquals(expected, written.matches(otherWritten), one + " and " + other);
        assertEquals(expected, InterceptorBindings.ofValues(List.of(written)).equals(InterceptorBindings.ofValues(
            List.of(otherWritten))), one + " and " + other);
    }

    @Test
    void aComposedQualifierComparesAsTheInterceptorBindingItIs() {
        assertComparedAlike(audited(Level.HIGH, "", "a"), audited("HIGH", "", "b"), true);
        assertComparedAlike(audited(Level.HIGH, "eu", "a"), audited(Level.HIGH, "us", "a"), false);
        assertComparedAlike(audited(Level.LOW, "", "a"), audited(Level.HIGH, "", "a"), false);
        AnnotationValue<?> nested = AnnotationValue.builder(Audited.class)
            .member("region", AnnotationValue.builder(Origin.class).member("city", "Prague").build()).build();
        AnnotationValue<?> sameNested = AnnotationValue.builder(Audited.class)
            .member("region", AnnotationValue.builder(Origin.class).member("city", "Prague").build()).build();
        AnnotationValue<?> otherNested = AnnotationValue.builder(Audited.class)
            .member("region", AnnotationValue.builder(Origin.class).member("city", "Brno").build()).build();
        assertComparedAlike(nested, sameNested, true);
        assertComparedAlike(nested, otherNested, false);
    }

    @Test
    void aLiteralIsTheSameQualifierAndInterceptorBinding() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = CDI.current().getBeanManager();
            List<AuditedLiteral> literals = List.of(
                new AuditedLiteral(Level.HIGH, "eu", "a"),
                new AuditedLiteral(Level.HIGH, "eu", "b"),
                new AuditedLiteral(Level.HIGH, "us", "a"),
                new AuditedLiteral(Level.LOW, "eu", "a"));
            for (AuditedLiteral one : literals) {
                for (AuditedLiteral other : literals) {
                    boolean expected = one.level() == other.level() && one.region().equals(other.region());
                    assertEquals(expected, manager.areQualifiersEquivalent(one, other));
                    assertEquals(expected, manager.areInterceptorBindingsEquivalent(one, other));
                    if (expected) {
                        assertEquals(manager.getQualifierHashCode(one), manager.getQualifierHashCode(other));
                        assertEquals(manager.getInterceptorBindingHashCode(one),
                            manager.getInterceptorBindingHashCode(other));
                    }
                }
                assertEquals(manager.getQualifierHashCode(one), manager.getInterceptorBindingHashCode(one));
            }
        }
    }

    @Test
    void aComposedValueLeavingAMemberOutSelectsTheBeanCompiledWithItsDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            MicronautInstance<Book> books = container.createInstance().select(Book.class, Any.Literal.INSTANCE);
            assertEquals(TopBook.class, books.select(AnnotationValue.builder(Shelf.class).build()).get().getClass());
            assertEquals(BottomBook.class,
                books.select(AnnotationValue.builder(Shelf.class).value("bottom").build()).get().getClass());
        }
    }
}
