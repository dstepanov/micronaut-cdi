package io.micronaut.cdi.el.producer;

import io.micronaut.context.ApplicationContext;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A name resolves the bean of that name, a producer's as well as a class's. The resolver asks for a
 * reference typed with the bean class, which for a producer is the class declaring it.
 */
class NamedProducerExpressionTest {

    @ApplicationScoped
    public static class Producers {

        @Produces
        @Named("salutation")
        String salutation() {
            return "hi";
        }
    }

    @Test
    void aNameResolvesTheProducerOfThatName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);

            Object value = factory.createValueExpression(el, "${salutation}", String.class).getValue(el);

            assertEquals("hi", value);
        }
    }

    @Test
    void theTypeOfAProducedBeanIsWhatItProducesRatherThanTheClassDeclaringIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ELContext el = new StandardELContext(ExpressionFactory.newInstance());

            Class<?> type = manager.getELResolver().getType(el, null, "salutation");

            assertEquals(String.class, type);
        }
    }
}
