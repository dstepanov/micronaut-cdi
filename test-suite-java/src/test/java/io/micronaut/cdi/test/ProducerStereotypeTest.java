package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProducerStereotypeTest {

    @Stereotype
    @Named
    @RequestScoped
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WebThing {
    }

    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {
        final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Marked> implements Marked {
        }
    }

    public static class Widget {
    }

    @jakarta.enterprise.context.Dependent
    public static class WidgetProducer {
        @Produces
        @Marked
        @WebThing
        public static Widget produceWidget = new Widget();

        @Produces
        @WebThing
        public Widget produceOther() {
            return new Widget();
        }
    }

    @Test
    void stereotypeOnProducerField() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(Widget.class, new Marked.Literal());
            assertEquals(1, beans.size());
            Bean<?> bean = beans.iterator().next();
            assertEquals(RequestScoped.class, bean.getScope(), "scope carried by the stereotype");
            assertEquals("produceWidget", bean.getName(), "name defaulted by the stereotype's Named");
            assertTrue(bean.getQualifiers().stream().noneMatch(q -> q.annotationType() == Named.class),
                "a stereotype-defaulted name is not a Named qualifier: " + bean.getQualifiers());
            assertTrue(bean.getQualifiers().stream().anyMatch(q -> q.annotationType() == Any.class));
        }
    }

    @Test
    void stereotypeOnProducerMethod() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(Widget.class, Default.Literal.INSTANCE);
            assertEquals(1, beans.size());
            Bean<?> bean = beans.iterator().next();
            assertEquals(RequestScoped.class, bean.getScope());
            assertEquals("produceOther", bean.getName());
            assertTrue(bean.getQualifiers().stream().anyMatch(q -> q.annotationType() == Default.class),
                "qualifiers: " + bean.getQualifiers());
        }
    }
}
