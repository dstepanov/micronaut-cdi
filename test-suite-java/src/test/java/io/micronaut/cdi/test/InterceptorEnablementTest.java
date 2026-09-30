package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.Interceptors;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An interceptor bound by an interceptor binding is enabled by the priority it declares, and one that declares none
 * is not enabled: it is neither reported by the bean manager nor invoked. What the bean manager reports and what
 * runs are the same interceptors. The SE bootstrap may enable one without a priority. An interceptor class a bean
 * names with {@code @Interceptors} is not subject to enablement and runs whether or not it declares a priority.
 */
class InterceptorEnablementTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Replaced {
    }

    @SuppressWarnings("serial")
    static final class ReplacedLiteral extends AnnotationLiteral<Replaced> implements Replaced {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Wrapped {
    }

    @SuppressWarnings("serial")
    static final class WrappedLiteral extends AnnotationLiteral<Wrapped> implements Wrapped {
    }

    @Replaced
    @jakarta.interceptor.Interceptor
    public static class UnprioritizedReplacingInterceptor {
        @AroundInvoke
        Object replace(InvocationContext ctx) {
            return "replaced";
        }
    }

    @Wrapped
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class PrioritizedWrappingInterceptor {
        @AroundInvoke
        Object wrap(InvocationContext ctx) throws Exception {
            return "[" + ctx.proceed() + "]";
        }
    }

    public static class NamedInterceptor {
        @AroundInvoke
        Object name(InvocationContext ctx) throws Exception {
            return "named " + ctx.proceed();
        }
    }

    @Dependent
    @Replaced
    public static class ReplacedTarget {
        public String call() {
            return "target";
        }
    }

    @Dependent
    @Replaced
    @Wrapped
    public static class WrappedTarget {
        public String call() {
            return "target";
        }
    }

    @Dependent
    @Interceptors(NamedInterceptor.class)
    public static class NamingTarget {
        public String call() {
            return "target";
        }
    }

    @Test
    void anInterceptorWithoutAPriorityIsNotReportedAndDoesNotRun() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);

            assertEquals(List.of(), manager.resolveInterceptors(InterceptionType.AROUND_INVOKE, new ReplacedLiteral()));
            assertEquals("target", context.getBean(ReplacedTarget.class).call());
        }
    }

    @Test
    void theInterceptorsThatRunAreTheOnesTheBeanManagerReports() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);

            List<Interceptor<?>> reported = manager.resolveInterceptors(InterceptionType.AROUND_INVOKE,
                new ReplacedLiteral(), new WrappedLiteral());
            assertEquals(List.of(PrioritizedWrappingInterceptor.class),
                reported.stream().<Class<?>>map(Interceptor::getBeanClass).toList());
            assertEquals("[target]", context.getBean(WrappedTarget.class).call());
        }
    }

    /**
     * The SE bootstrap enables an interceptor that declares no priority, in the container it builds: it is then
     * reported and invoked.
     */
    @Test
    void theBootstrapEnablesAnInterceptorWithoutAPriority() {
        try (SeContainer container = SeContainerInitializer.newInstance()
            .enableInterceptors(UnprioritizedReplacingInterceptor.class)
            .initialize()) {
            List<Interceptor<?>> reported = container.getBeanManager()
                .resolveInterceptors(InterceptionType.AROUND_INVOKE, new ReplacedLiteral());
            assertEquals(List.of(UnprioritizedReplacingInterceptor.class),
                reported.stream().<Class<?>>map(Interceptor::getBeanClass).toList());
            assertEquals("replaced", container.select(ReplacedTarget.class).get().call());
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Listed {
    }

    @SuppressWarnings("serial")
    static final class ListedLiteral extends AnnotationLiteral<Listed> implements Listed {
    }

    @Listed
    @jakarta.interceptor.Interceptor
    public static class AlphaListedInterceptor {
        @AroundInvoke
        Object alpha(InvocationContext ctx) throws Exception {
            return "alpha " + ctx.proceed();
        }
    }

    @Listed
    @jakarta.interceptor.Interceptor
    public static class ZetaListedInterceptor {
        @AroundInvoke
        Object zeta(InvocationContext ctx) throws Exception {
            return "zeta " + ctx.proceed();
        }
    }

    @Listed
    @jakarta.interceptor.Interceptor
    @Priority(9000)
    public static class PrioritizedListedInterceptor {
        @AroundInvoke
        Object prioritized(InvocationContext ctx) throws Exception {
            return "prioritized " + ctx.proceed();
        }
    }

    @Dependent
    @Listed
    public static class ListedTarget {
        public String call() {
            return "target";
        }
    }

    /**
     * The interceptors the SE bootstrap enables run in the order it was given them, after the ones a priority
     * enables, and the bean manager reports them in that order.
     */
    @Test
    void theBootstrapKeepsTheOrderItEnabledTheInterceptorsIn() {
        try (SeContainer container = SeContainerInitializer.newInstance()
            .enableInterceptors(ZetaListedInterceptor.class, AlphaListedInterceptor.class)
            .initialize()) {
            List<Interceptor<?>> reported = container.getBeanManager()
                .resolveInterceptors(InterceptionType.AROUND_INVOKE, new ListedLiteral());
            assertEquals(List.of(PrioritizedListedInterceptor.class, ZetaListedInterceptor.class,
                    AlphaListedInterceptor.class),
                reported.stream().<Class<?>>map(Interceptor::getBeanClass).toList());
            assertEquals("prioritized zeta alpha target", container.select(ListedTarget.class).get().call());
        }
    }

    @Test
    void anInterceptorNamedByTheBeanRunsWithoutAPriority() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("named target", context.getBean(NamingTarget.class).call());
        }
    }
}
