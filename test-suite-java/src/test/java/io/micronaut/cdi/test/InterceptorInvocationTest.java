package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.AroundTimeout;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interceptor the bean manager hands out is invoked the way the interceptors implementation invokes it in a
 * chain: the same interceptor methods, in the same order, throwing the same exceptions.
 */
class InterceptorInvocationTest {

    static final List<String> CALLS = new ArrayList<>();

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Stacked {
    }

    @SuppressWarnings("serial")
    static final class StackedLiteral extends AnnotationLiteral<Stacked> implements Stacked {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Refusing {
    }

    @SuppressWarnings("serial")
    static final class RefusingLiteral extends AnnotationLiteral<Refusing> implements Refusing {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Clocked {
    }

    @SuppressWarnings("serial")
    static final class ClockedLiteral extends AnnotationLiteral<Clocked> implements Clocked {
    }

    public static class BaseStackedInterceptor {
        @AroundInvoke
        private Object around(InvocationContext ctx) throws Exception {
            CALLS.add("base around");
            return ctx.proceed();
        }
    }

    @Stacked
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class StackedInterceptor extends BaseStackedInterceptor {
        @AroundInvoke
        private Object around(InvocationContext ctx) throws Exception {
            CALLS.add("own around");
            return ctx.proceed();
        }
    }

    @Refusing
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class RefusingInterceptor {
        static Exception failure = new IOException("unset");

        @AroundInvoke
        private Object refuse(InvocationContext ctx) throws Exception {
            throw failure;
        }
    }

    @Clocked
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class BusinessOnlyInterceptor {
        @AroundInvoke
        Object business(InvocationContext ctx) throws Exception {
            CALLS.add("business only");
            return ctx.proceed();
        }
    }

    @Clocked
    @jakarta.interceptor.Interceptor
    @Priority(200)
    public static class ClockedInterceptor {
        @AroundInvoke
        Object business(InvocationContext ctx) throws Exception {
            CALLS.add("clocked business");
            return ctx.proceed();
        }

        @AroundTimeout
        Object timeout(InvocationContext ctx) throws Exception {
            CALLS.add("clocked timeout");
            return ctx.proceed();
        }
    }

    /**
     * A private method is not overridden, so a class and its superclass that each declare a private interceptor
     * method of one name declare two interceptor methods, and both are invoked, the superclass's first.
     */
    @Test
    void privateInterceptorMethodsOfOneNameInAClassAndItsSuperclassAreBothInvoked() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Interceptor<StackedInterceptor> stacked = only(manager, InterceptionType.AROUND_INVOKE,
                new StackedLiteral());

            Object result = stacked.intercept(InterceptionType.AROUND_INVOKE,
                context.getBean(StackedInterceptor.class), new Invocation());

            assertEquals(List.of("base around", "own around", "proceeded"), CALLS);
            assertEquals("target", result);
        }
    }

    /**
     * A private interceptor method is reached reflectively, and what it throws still arrives as it was thrown
     * rather than inside the exception of the reflective call.
     */
    @Test
    void whatAPrivateInterceptorMethodThrowsArrivesUnchanged() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Interceptor<RefusingInterceptor> refusing = only(manager, InterceptionType.AROUND_INVOKE,
                new RefusingLiteral());
            RefusingInterceptor instance = context.getBean(RefusingInterceptor.class);

            IOException checked = new IOException("refused");
            RefusingInterceptor.failure = checked;
            assertSame(checked, assertThrows(IOException.class,
                () -> refusing.intercept(InterceptionType.AROUND_INVOKE, instance, new Invocation())));

            IllegalStateException unchecked = new IllegalStateException("refused");
            RefusingInterceptor.failure = unchecked;
            assertSame(unchecked, assertThrows(IllegalStateException.class,
                () -> refusing.intercept(InterceptionType.AROUND_INVOKE, instance, new Invocation())));
        }
    }

    /**
     * What an interceptor is reported to interpose on is what it declares: the bean manager does not report an
     * interceptor with {@code @AroundInvoke} methods alone for a timeout, and one that declares an
     * {@code @AroundTimeout} method is invoked through that method.
     */
    @Test
    void aTimeoutIsInterposedOnByTheInterceptorsThatDeclareAnAroundTimeoutMethod() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(List.of(BusinessOnlyInterceptor.class, ClockedInterceptor.class),
                manager.resolveInterceptors(InterceptionType.AROUND_INVOKE, new ClockedLiteral())
                    .stream().<Class<?>>map(Interceptor::getBeanClass).toList());

            Interceptor<ClockedInterceptor> clocked = only(manager, InterceptionType.AROUND_TIMEOUT,
                new ClockedLiteral());
            assertEquals(ClockedInterceptor.class, clocked.getBeanClass());
            assertTrue(clocked.intercepts(InterceptionType.AROUND_TIMEOUT));
            assertFalse(clocked.intercepts(InterceptionType.POST_CONSTRUCT));
            clocked.intercept(InterceptionType.AROUND_TIMEOUT, context.getBean(ClockedInterceptor.class),
                new Invocation());
            assertEquals(List.of("clocked timeout", "proceeded"), CALLS);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Interceptor<T> only(BeanManager manager, InterceptionType type, Annotation binding) {
        List<Interceptor<?>> interceptors = manager.resolveInterceptors(type, binding);
        assertEquals(1, interceptors.size());
        return (Interceptor<T>) interceptors.get(0);
    }

    /** An invocation that records being proceeded into. */
    private static final class Invocation implements InvocationContext {

        @Override
        public Object proceed() {
            CALLS.add("proceeded");
            return "target";
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
        public Method getMethod() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
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
        public Map<String, Object> getContextData() {
            return Map.of();
        }

        @Override
        public Set<Annotation> getInterceptorBindings() {
            return Set.of();
        }
    }
}
