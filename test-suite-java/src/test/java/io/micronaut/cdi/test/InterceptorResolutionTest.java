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

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Tied {
    }

    @SuppressWarnings("serial")
    static final class TiedLiteral extends AnnotationLiteral<Tied> implements Tied {
    }

    @Tied
    @jakarta.interceptor.Interceptor
    @Priority(300)
    public static class ZetaTiedInterceptor {
        @AroundInvoke
        Object zeta(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Tied
    @jakarta.interceptor.Interceptor
    @Priority(300)
    public static class AlphaTiedInterceptor {
        @AroundInvoke
        Object alpha(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Tied
    @jakarta.interceptor.Interceptor
    @io.micronaut.core.annotation.Order(50)
    public static class EarlyTiedInterceptor {
        @AroundInvoke
        Object early(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    /**
     * The order the bean manager reports is the order the interceptors implementation invokes: by the priority of
     * the specification, then by Micronaut's own order where a class declares that instead, and two of one
     * priority by their class names, so that the order is one and the same every time.
     */
    @Test
    void interceptorsOfOnePriorityAreOrderedByTheirNames() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            List<Interceptor<?>> interceptors = manager.resolveInterceptors(InterceptionType.AROUND_INVOKE,
                new TiedLiteral());
            assertEquals(List.of(EarlyTiedInterceptor.class, AlphaTiedInterceptor.class, ZetaTiedInterceptor.class),
                interceptors.stream().<Class<?>>map(Interceptor::getBeanClass).toList());
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

    /**
     * Section 2.5 of Jakarta Interceptors has an exception travel through the chain as it was thrown, and
     * {@code Interceptor.intercept} declares {@code Exception}: what the invocation threw reaches the caller as
     * it is, checked or not, rather than wrapped in something the caller cannot catch.
     */
    @Test
    void anInterceptorLetsACheckedExceptionOfTheInvocationThrough() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            @SuppressWarnings("unchecked")
            Interceptor<GuardInterceptor> guard = (Interceptor<GuardInterceptor>) manager
                .resolveInterceptors(InterceptionType.AROUND_INVOKE, new GuardedLiteral()).get(0);
            GuardInterceptor instance = context.getBean(GuardInterceptor.class);
            InvocationContext invocation = new FailingInvocation(new java.io.IOException("refused"));
            java.io.IOException thrown = assertThrows(java.io.IOException.class,
                () -> guard.intercept(InterceptionType.AROUND_INVOKE, instance, invocation));
            assertEquals("refused", thrown.getMessage());
        }
    }

    /** An invocation whose proceed throws what it was given. */
    private record FailingInvocation(Exception failure) implements InvocationContext {

        @Override
        public Object proceed() throws Exception {
            throw failure;
        }

        @Override
        public Object getTarget() {
            return null;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public java.lang.reflect.Method getMethod() {
            return null;
        }

        @Override
        public java.lang.reflect.Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(Object[] params) {
        }

        @Override
        public java.util.Map<String, Object> getContextData() {
            return java.util.Map.of();
        }

        @Override
        public java.util.Set<java.lang.annotation.Annotation> getInterceptorBindings() {
            return java.util.Set.of();
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
