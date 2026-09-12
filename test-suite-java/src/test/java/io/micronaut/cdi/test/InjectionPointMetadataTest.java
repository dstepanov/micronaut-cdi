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
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a dependent bean is told about the injection point it was created for.
 *
 * <p>Section 2.5.2.5 lets a bean in the dependent pseudo-scope be injected with the {@code InjectionPoint} of the
 * point it is being injected into. It names the member, the type that was asked for, the qualifiers the point
 * declared, the bean that declares the point, and describes the member as an {@code Annotated} - a field where the
 * point is a field and a parameter where it is a parameter of a constructor or an initializer.</p>
 */
class InjectionPointMetadataTest {

    @Test
    void aFieldInjectionPointNamesItsFieldAndTheBeanThatDeclaresIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Mast mast = container.createInstance().select(Mast.class).get();
            InjectionPoint at = mast.pennant.at();
            assertEquals("pennant", at.getMember().getName());
            assertEquals(Mast.class, at.getMember().getDeclaringClass());
            assertEquals(Pennant.class, at.getType());
            assertFalse(at.isTransient());
            assertFalse(at.isDelegate());
            assertNotNull(at.getBean());
            assertEquals(Mast.class, at.getBean().getBeanClass());
            AnnotatedField<?> annotated = assertInstanceOf(AnnotatedField.class, at.getAnnotated());
            assertEquals("pennant", annotated.getJavaMember().getName());
            assertEquals(Pennant.class, annotated.getBaseType());
            assertTrue(annotated.isAnnotationPresent(Inject.class));
        }
    }

    @Test
    void aConstructorParameterInjectionPointIsDescribedAsAParameter() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Yard yard = container.createInstance().select(Yard.class).get();
            InjectionPoint at = yard.pennant.at();
            AnnotatedParameter<?> annotated = assertInstanceOf(AnnotatedParameter.class, at.getAnnotated());
            assertEquals(0, annotated.getPosition());
            assertEquals(Pennant.class, annotated.getBaseType());
            assertEquals(Yard.class, at.getMember().getDeclaringClass());
        }
    }

    @Test
    void theQualifiersOfTheInjectionPointAreTheOnesThePointDeclared() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Halyard halyard = container.createInstance().select(Halyard.class).get();
            Set<Annotation> qualifiers = halyard.pennant.at().getQualifiers();
            assertTrue(qualifiers.stream().anyMatch(qualifier -> qualifier instanceof Hoisted),
                () -> "the qualifier the field declared is not among " + qualifiers);
        }
    }

    /**
     * Qualifies one of the injection points, so that the point's own qualifiers can be read back.
     */
    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Hoisted {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Hoisted> implements Hoisted {
        }
    }

    /**
     * What is produced for an injection point, carrying the point it was produced for.
     *
     * @param at The injection point
     */
    public record Pennant(InjectionPoint at) {
    }

    /**
     * Produces a pennant for whichever point asked for one.
     */
    @Singleton
    public static class Sailmaker {

        /**
         * @param at The point being injected into
         * @return The pennant for it
         */
        @Produces
        @Dependent
        Pennant pennant(InjectionPoint at) {
            return new Pennant(at);
        }

        /**
         * @param at The point being injected into
         * @return The pennant for it
         */
        @Produces
        @Dependent
        @Hoisted
        Pennant hoistedPennant(InjectionPoint at) {
            return new Pennant(at);
        }
    }

    /**
     * Asks for one in a field.
     */
    @Singleton
    public static class Mast {

        @Inject
        Pennant pennant;
    }

    /**
     * Asks for one in a constructor parameter.
     */
    @Singleton
    public static class Yard {

        final Pennant pennant;

        @Inject
        Yard(Pennant pennant) {
            this.pennant = pennant;
        }
    }

    /**
     * Asks for a qualified one.
     */
    @Singleton
    public static class Halyard {

        @Inject
        @Hoisted
        Pennant pennant;
    }
}
