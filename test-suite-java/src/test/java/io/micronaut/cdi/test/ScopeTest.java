package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeTest {

    @ApplicationScoped
    static class AppBean {
        private final AtomicInteger counter = new AtomicInteger();
        String hello() {
            return "hello";
        }
        int count() {
            return counter.incrementAndGet();
        }
    }

    @Dependent
    static class DependentBean {
        String hello() {
            return "dependent";
        }
    }

    @RequestScoped
    static class RequestBean {
        String hello() {
            return "request";
        }
    }

    @ApplicationScoped
    static class Holder {
        @Inject
        AppBean app;
        @Inject
        DependentBean dependent;

        // a normal-scoped bean is reached through a client proxy, and a proxy delegates methods, not fields
        String appSays() {
            return app.hello();
        }

        String dependentSays() {
            return dependent.hello();
        }
    }

    @Test
    void applicationScopedIsOneInstance() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AppBean first = context.getBean(AppBean.class);
            AppBean second = context.getBean(AppBean.class);
            assertEquals("hello", first.hello());
            // the two references are client proxies of the one instance the scope holds, so the state one of
            // them reaches is the state the other reaches
            assertEquals(1, first.count());
            assertEquals(2, second.count());
        }
    }

    @Test
    void dependentIsANewInstanceEveryTime() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertNotSame(context.getBean(DependentBean.class), context.getBean(DependentBean.class));
        }
    }

    @Test
    void injectionResolvesBothScopes() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Holder holder = context.getBean(Holder.class);
            assertEquals("hello", holder.appSays());
            assertEquals("dependent", holder.dependentSays());
        }
    }

    @Test
    void requestScopedNeedsAnActiveRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestBean bean = context.getBean(RequestBean.class);
            assertThrows(ContextNotActiveException.class, bean::hello);
        }
    }
}
