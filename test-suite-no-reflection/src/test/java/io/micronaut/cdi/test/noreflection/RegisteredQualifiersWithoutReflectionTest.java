package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.RegisterAnnotations;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier with members whose annotation type the application registers for a generated builder is made an
 * instance of without the reflection module: the qualifiers of a bean are reported as annotation instances.
 */
@RegisterAnnotations(RegisteredQualifiersWithoutReflectionTest.Topping.class)
public class RegisteredQualifiersWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Topping {
        String value();
    }

    public interface Dessert {
    }

    @Dependent
    @Topping("cream")
    public static class Pie implements Dessert {
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
}
