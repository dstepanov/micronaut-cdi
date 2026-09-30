package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A qualifier given as an {@code AnnotationValue} and the same qualifier given as an annotation literal select
 * the same beans and notify the same observers: the literal, which this project's reflection module reads, is
 * compared as the values it holds.
 */
class SelectByAnnotationValueTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Grade {

        int value();

        @Nonbinding String remark() default "";
    }

    static final class GradeLiteral extends AnnotationLiteral<Grade> implements Grade {

        private final int value;
        private final String remark;

        GradeLiteral(int value, String remark) {
            this.value = value;
            this.remark = remark;
        }

        @Override
        public int value() {
            return value;
        }

        @Override
        public String remark() {
            return remark;
        }
    }

    interface Paper {
    }

    @Grade(1)
    @Dependent
    static class First implements Paper {
    }

    @Grade(value = 2, remark = "second")
    @Dependent
    static class Second implements Paper {
    }

    record Marked(String by) {
    }

    @ApplicationScoped
    static class Examiner {

        final List<String> seen = new ArrayList<>();

        void first(@Observes @Grade(1) Marked marked) {
            seen.add("first:" + marked.by());
        }

        void second(@Observes @Grade(2) Marked marked) {
            seen.add("second:" + marked.by());
        }

        List<String> seen() {
            return List.copyOf(seen);
        }
    }

    private static AnnotationValue<Grade> grade(int value, String remark) {
        return AnnotationValue.builder(Grade.class).value(value).member("remark", remark).build();
    }

    @Test
    void theValueAndTheLiteralSelectTheSameBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            MicronautInstance<Paper> papers = container.createInstance().select(Paper.class, Any.Literal.INSTANCE);
            for (int value = 1; value <= 2; value++) {
                Class<?> byLiteral = papers.select(new GradeLiteral(value, "a")).get().getClass();
                Class<?> byValue = papers.select(grade(value, "b")).get().getClass();
                assertSame(byLiteral, byValue);
                assertEquals(container.getBeans(Paper.class, new GradeLiteral(value, "c")),
                    container.getBeans(Argument.of(Paper.class), grade(value, "d")));
            }
            assertEquals(First.class, papers.select(grade(1, "")).get().getClass());
            assertEquals(Second.class, papers.select(new GradeLiteral(2, "")).get().getClass());
        }
    }

    @Test
    void theValueAndTheLiteralNotifyTheSameObservers() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            MicronautEvent<Object> events = container.getEvent();
            Examiner examiner = context.getBean(Examiner.class);
            events.select(Marked.class, new GradeLiteral(1, "x")).fire(new Marked("literal"));
            events.select(Marked.class, grade(1, "y")).fire(new Marked("value"));
            events.select(Marked.class, grade(2, "z")).fire(new Marked("value"));
            assertEquals(List.of("first:literal", "first:value", "second:value"), examiner.seen());
        }
    }
}
