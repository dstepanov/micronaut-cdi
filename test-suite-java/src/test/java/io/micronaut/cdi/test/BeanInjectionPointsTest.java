package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injection points a bean reports are the ones its author wrote, whatever package their types are in: the
 * parameters of its constructor, leaving out only what the container passes a generated constructor, and, for a
 * producer method, its parameters (section 2.2.2.2).
 */
class BeanInjectionPointsTest {

    @Dependent
    public static class Part {
    }

    @Dependent
    public static class Assembled {
        @Inject
        public Assembled(Part part, @Named("spare") Part spare) {
        }
    }

    @Dependent
    public static class Spares {
        @Produces
        @Named("spare")
        Part spare() {
            return new Part();
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    public @interface Checked {
    }

    @Checked
    @Interceptor
    @Priority(100)
    public static class CheckingInterceptor {
        @AroundInvoke
        Object check(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Dependent
    @Checked
    public static class CheckedAssembly {
        @Inject
        public CheckedAssembly(Part part) {
        }

        public void work() {
        }
    }

    public record Product(Part part) {
    }

    @Dependent
    public static class Factory {
        @Produces
        Product produce(Part part) {
            return new Product(part);
        }

        void dispose(@Disposes Product product, Assembled witness) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanManager manager, Class<T> type) {
        return (Bean<T>) manager.resolve(manager.getBeans(type));
    }

    @Test
    void theParametersOfAConstructorAreInjectionPointsWhateverTheirPackage() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<Assembled> bean = beanOf(context.getBean(BeanManager.class), Assembled.class);

            Set<InjectionPoint> points = bean.getInjectionPoints();

            assertEquals(2, points.size());
            for (InjectionPoint point : points) {
                assertEquals(Part.class, point.getType());
                assertSame(bean, point.getBean());
            }
            assertEquals(1, points.stream().filter(point -> point.getQualifiers().contains(Default.Literal.INSTANCE)).count());
        }
    }

    @Test
    void whatTheContainerPassesAnInterceptedBeanIsNotAnInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<CheckedAssembly> bean = beanOf(context.getBean(BeanManager.class), CheckedAssembly.class);

            Set<InjectionPoint> points = bean.getInjectionPoints();

            assertEquals(1, points.size());
            assertEquals(Part.class, points.iterator().next().getType());
        }
    }

    @Test
    void theParametersOfAProducerMethodAreInjectionPointsOfItsBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<Product> bean = beanOf(context.getBean(BeanManager.class), Product.class);

            Set<InjectionPoint> points = bean.getInjectionPoints();

            // the parameter of the producer, and not the ones of the disposer
            assertEquals(1, points.size());
            InjectionPoint point = points.iterator().next();
            assertEquals(Part.class, point.getType());
            assertSame(bean, point.getBean());
            assertTrue(point.getQualifiers().contains(Default.Literal.INSTANCE));
            assertEquals("produce", point.getMember().getName());
            assertEquals(Factory.class, point.getMember().getDeclaringClass());
        }
    }
}
