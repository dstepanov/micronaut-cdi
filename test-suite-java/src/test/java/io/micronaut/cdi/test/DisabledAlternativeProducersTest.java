package io.micronaut.cdi.test;

import io.micronaut.cdi.annotation.UnselectedAlternative;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A producer of a bean that is not enabled is not enabled either (section 5.1.2): what an alternative class
 * declares is produced only where the class was selected.
 */
class DisabledAlternativeProducersTest {

    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Left {
        final class Literal extends AnnotationLiteral<Left> implements Left {
        }
    }

    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Right {
        final class Literal extends AnnotationLiteral<Right> implements Right {
        }
    }

    public static class Glove {
    }

    @Alternative
    @Dependent
    public static class LeftGloves {
        @Produces
        @Left
        public static final Glove FIELD = new Glove();

        @Produces
        @Left
        public Glove method() {
            return FIELD;
        }
    }

    @Alternative
    @Dependent
    public static class RightGloves {
        @Produces
        @Right
        public static final Glove FIELD = new Glove();

        @Produces
        @Right
        public Glove method() {
            return FIELD;
        }
    }

    @Test
    void theProducersOfAnUnselectedAlternativeClassProduceNothing() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(0, manager.getBeans(Glove.class, new Left.Literal()).size());
            assertEquals(0, manager.getBeans(Glove.class, new Right.Literal()).size());
        }
    }

    @Test
    void selectingTheClassEnablesItsProducersAndNoOthers() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            UnselectedAlternative.SELECTED_CLASSES, LeftGloves.class.getName()))) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(2, manager.getBeans(Glove.class, new Left.Literal()).size());
            assertEquals(0, manager.getBeans(Glove.class, new Right.Literal()).size());
        }
    }
}
