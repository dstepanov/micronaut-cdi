package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorResolutionTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Guarded {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Audited {
    }

    @SuppressWarnings("serial")
    static final class GuardedLiteral extends AnnotationLiteral<Guarded> implements Guarded {
    }

    @SuppressWarnings("serial")
    static final class AuditedLiteral extends AnnotationLiteral<Audited> implements Audited {
    }

    @Guarded
    @jakarta.interceptor.Interceptor
    @Priority(200)
    public static class GuardInterceptor {
        @AroundInvoke
        Object guard(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Audited
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class AuditInterceptor {
        @AroundInvoke
        Object audit(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Guarded
    @jakarta.interceptor.Interceptor
    public static class NotEnabledInterceptor {
        @AroundInvoke
        Object never(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Test
    void anInterceptorResolvesByItsBindingAndOrdersByPriority() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            List<Interceptor<?>> interceptors = manager.resolveInterceptors(InterceptionType.AROUND_INVOKE,
                new GuardedLiteral(), new AuditedLiteral());
            assertEquals(List.of(AuditInterceptor.class, GuardInterceptor.class),
                interceptors.stream().<Class<?>>map(Interceptor::getBeanClass).toList(),
                "the enabled interceptors, lowest priority first, and never the one without a priority");
            assertEquals(1, interceptors.get(0).getInterceptorBindings().size());
            assertTrue(interceptors.get(0).getInterceptorBindings().contains(new AuditedLiteral()));
            assertTrue(interceptors.get(0).intercepts(InterceptionType.AROUND_INVOKE));
        }
    }

    @Test
    void resolvingWithoutABindingIsRefused() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertThrows(IllegalArgumentException.class,
                () -> manager.resolveInterceptors(InterceptionType.AROUND_INVOKE));
        }
    }

    @Test
    void resolvingWithADuplicateBindingIsRefused() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertThrows(IllegalArgumentException.class,
                () -> manager.resolveInterceptors(InterceptionType.AROUND_INVOKE,
                    new GuardedLiteral(), new GuardedLiteral()));
        }
    }

    @Test
    void resolvingWithANonBindingAnnotationIsRefused() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertThrows(IllegalArgumentException.class,
                () -> manager.resolveInterceptors(InterceptionType.AROUND_INVOKE,
                    (java.lang.annotation.Annotation) () -> Override.class));
        }
    }
}
