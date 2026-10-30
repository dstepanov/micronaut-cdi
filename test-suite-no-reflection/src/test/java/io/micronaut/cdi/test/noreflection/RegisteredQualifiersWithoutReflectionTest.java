package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationBuilder;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.RegisterAnnotations;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier with members whose annotation type the application registers for a generated builder is made an
 * instance of without the reflection module: the qualifiers of a bean are reported as annotation instances, and
 * such an instance is read back without it, since it carries its values.
 */
@RegisterAnnotations(RegisteredQualifiersWithoutReflectionTest.Topping.class)
public class RegisteredQualifiersWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Topping {
        String value();

        @Nonbinding String note() default "";

        /**
         * A literal a program writes, which neither carries its values nor is made by the builder.
         */
        class Literal extends AnnotationLiteral<Topping> implements Topping {

            private final String value;
            private final String note;

            Literal(String value, String note) {
                this.value = value;
                this.note = note;
            }

            @Override
            public String value() {
                return value;
            }

            @Override
            public String note() {
                return note;
            }
        }
    }

    public interface Dessert {
    }

    @Dependent
    @Topping(value = "cream", note = "warm")
    public static class Pie implements Dessert {
    }

    @Test
    void aLiteralOfARegisteredAnnotationTypeIsReadByItsBuilder() {
        try (ApplicationContext ignored = ApplicationContext.run()) {
            assertEquals(Pie.class,
                CDI.current().select(Dessert.class, new Topping.Literal("cream", "cold")).get().getClass());
            assertTrue(CDI.current().select(Dessert.class, new Topping.Literal("custard", "warm")).isUnsatisfied(),
                "@Topping(\"custard\") is not the @Topping(\"cream\") of the bean");
        }
    }

    @Test
    void aLiteralReadByItsBuilderIsNeverFormatted() {
        // AnnotationLiteral.toString reads every member reflectively: a literal the builder reads is not formatted
        Topping unformattable = new Topping.Literal("cream", "cold") {
            @Override
            public String toString() {
                throw new IllegalStateException("the literal was formatted");
            }
        };
        try (ApplicationContext ignored = ApplicationContext.run()) {
            assertEquals(Pie.class, CDI.current().select(Dessert.class, unformattable).get().getClass());
        }
    }

    @Test
    void theQualifiersOfABeanAreInstancesOfARegisteredAnnotationType() {
        try (ApplicationContext ignored = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            Bean<?> pie = container.getBeans(Argument.of(Dessert.class),
                AnnotationValue.builder(Topping.class).value("cream").build()).iterator().next();
            Topping topping = null;
            for (Annotation qualifier : pie.getQualifiers()) {
                if (qualifier instanceof Topping found) {
                    topping = found;
                }
            }
            assertTrue(topping != null, "the qualifiers " + pie.getQualifiers());
            assertEquals("cream", topping.value());
            assertEquals(Topping.class, topping.annotationType());
        }
    }

    @Test
    void anInstanceOfARegisteredAnnotationTypeSelectsWithoutTheReflectionModule() {
        try (ApplicationContext ignored = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            Bean<?> pie = container.getBeans(Argument.of(Dessert.class),
                AnnotationValue.builder(Topping.class).value("cream").build()).iterator().next();
            Annotation reported = pie.getQualifiers().stream()
                .filter(Topping.class::isInstance).findFirst().orElseThrow();
            assertEquals(java.util.Set.of(pie), container.getBeans(Dessert.class, reported));
            assertEquals(Pie.class, CDI.current().select(Dessert.class, reported).get().getClass());
            // one the generated builder made, with a value of the member that does not bind the bean was not given
            Topping built = AnnotationBuilder.find(Topping.class).orElseThrow()
                .build(AnnotationValue.builder(Topping.class).value("cream").member("note", "cold").build());
            assertEquals(Pie.class, CDI.current().select(Dessert.class, built).get().getClass());
        }
    }
}
