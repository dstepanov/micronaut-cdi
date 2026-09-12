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
package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The shapes a producer and its disposer may take: a static member, a primitive type, a producer that produces
 * nothing, and one disposer for every qualification of a type.
 *
 * <p>Section 3.3 and section 3.4 let a producer method and a producer field be static, in which case the bean is
 * produced without an instance of the class that declares it. Section 3.3.2 lets a producer of a dependent
 * instance produce null, and the null is what is injected. A producer of a primitive type produces a bean of that
 * type and of the class that boxes it (section 2.2.1). Section 3.3.7 binds a disposer to every producer of the
 * class whose qualifiers it matches, and a disposed parameter qualified {@code @Any} matches all of them.</p>
 */
class ProducerVarietyTest {

    @BeforeEach
    void clear() {
        Foghorn.DISPOSED.clear();
    }

    @Test
    void aStaticProducerProducesWithoutAnInstanceOfItsClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("from a static method",
                container.createInstance().select(String.class, new Blown.Literal()).get());
            assertEquals(42,
                container.createInstance().select(Integer.class, new Blown.Literal()).get());
        }
    }

    @Test
    void aDependentProducerMayProduceNothing() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertNull(container.createInstance().select(Foghorn.class, new Silent.Literal()).get());
        }
    }

    @Test
    void aPrimitiveProducerProducesTheClassThatBoxesItToo() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(7L, container.createInstance().select(long.class, new Blown.Literal()).get());
            assertEquals(7L, container.createInstance().select(Long.class, new Blown.Literal()).get());
        }
    }

    @Test
    void aDisposerQualifiedAnyDisposesOfEveryProducerOfTheType() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Foghorn> blown = container.createInstance().select(Foghorn.class, new Blown.Literal());
            Instance.Handle<Foghorn> blownHandle = blown.getHandle();
            blownHandle.get();
            blownHandle.destroy();
            assertEquals(List.of("blown"), Foghorn.DISPOSED);
        }
    }

    /**
     * Tells what this test produces apart from everything else the suite produces.
     */
    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Blown {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Blown> implements Blown {
        }
    }

    /**
     * The second qualification of the same type, so that one disposer has two producers to match.
     */
    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Silent {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Silent> implements Silent {
        }
    }

    /**
     * What the producers produce, and where the disposals are recorded.
     *
     * @param sound What it sounds like
     */
    public record Foghorn(String sound) {

        /**
         * What was disposed of, in the order it was.
         */
        public static final List<String> DISPOSED = new CopyOnWriteArrayList<>();
    }

    /**
     * Declares static producers, producers of a primitive, one that produces nothing, and one disposer for both
     * qualifications of the type it produces.
     */
    @Singleton
    public static class Harbour {

        /**
         * A static producer field, produced without an instance of this class.
         */
        @Produces
        @Dependent
        @Blown
        static String announcement = "from a static method";

        /**
         * @return A number, from a static producer method
         */
        @Produces
        @Dependent
        @Blown
        static Integer count() {
            return 42;
        }

        /**
         * @return A primitive, whose bean has the class that boxes it among its types
         */
        @Produces
        @Dependent
        @Blown
        long length() {
            return 7L;
        }

        /**
         * @return A foghorn
         */
        @Produces
        @Dependent
        @Blown
        Foghorn blown() {
            return new Foghorn("blown");
        }

        /**
         * @return Nothing, which a producer of a dependent instance may do
         */
        @Produces
        @Dependent
        @Silent
        Foghorn silent() {
            return null;
        }

        /**
         * Disposes of whichever of the two a lookup created.
         *
         * @param horn The foghorn being destroyed
         */
        void dispose(@Disposes @Any Foghorn horn) {
            Foghorn.DISPOSED.add(horn == null ? "nothing" : horn.sound());
        }
    }
}
