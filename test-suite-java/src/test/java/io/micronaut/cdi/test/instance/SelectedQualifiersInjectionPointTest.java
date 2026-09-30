package io.micronaut.cdi.test.instance;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injection point a dependent instance obtained through {@code Instance.select(...).get()} is
 * handed reports the qualifiers the lookup was narrowed by as well as those of the injected Instance.
 */
class SelectedQualifiersInjectionPointTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Red {

        final class Literal extends AnnotationLiteral<Red> implements Red {
            public static final Literal INSTANCE = new Literal();
        }
    }

    public interface Service {
        InjectionPoint injectedAt();
    }

    @Dependent
    @Red
    public static class RedService implements Service {

        @Inject
        InjectionPoint injectionPoint;

        @Override
        public InjectionPoint injectedAt() {
            return injectionPoint;
        }
    }

    @Dependent
    public static class Holder {

        @Inject
        @Any
        Instance<Service> services;
    }

    @Test
    void theSelectedQualifierIsAmongTheQualifiersOfTheInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Service red = context.getBean(Holder.class).services.select(Red.Literal.INSTANCE).get();
            Set<Annotation> qualifiers = red.injectedAt().getQualifiers();

            assertTrue(qualifiers.contains(Red.Literal.INSTANCE), "@Red was selected: " + qualifiers);
        }
    }
}
