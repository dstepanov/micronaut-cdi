package io.micronaut.cdi.test;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request is the work it is begun around rather than the thread that work started on: the beans of a request
 * live in the propagated context, so work handed to another thread is the same request there.
 */
class RequestContextTest {

    static final List<String> DISPOSED = new ArrayList<>();
    static final AtomicInteger COUNTER = new AtomicInteger();

    record Conn(int id) {
    }

    @RequestScoped
    static class RequestBean {
        private final int id = COUNTER.incrementAndGet();

        int id() {
            return id;
        }
    }

    @ApplicationScoped
    static class Producers {
        @Produces
        @Dependent
        Conn conn() {
            return new Conn(COUNTER.incrementAndGet());
        }

        void close(@Disposes Conn conn) {
            DISPOSED.add("conn" + conn.id());
        }
    }

    @RequestScoped
    static class OwnsAConn {
        @Inject
        Conn conn;

        int connId() {
            return conn.id();
        }
    }

    @ApplicationScoped
    static class InjectsTheController {
        @Inject
        RequestContextController controller;

        int idOfTheRequest(RequestBean bean) {
            controller.activate();
            try {
                return bean.id();
            } finally {
                controller.deactivate();
            }
        }
    }

    @ActivateRequestContext
    @Singleton
    static class EveryCallIsARequest {
        int idOfTheRequest(RequestBean bean) {
            return bean.id();
        }
    }

    @Singleton
    static class OneRequestPerCall {
        @ActivateRequestContext
        int idOfTheRequest(RequestBean bean) {
            return bean.id();
        }
    }

    @Test
    void aRequestScopedBeanNeedsARequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestBean bean = context.getBean(RequestBean.class);
            assertThrows(ContextNotActiveException.class, bean::id);
        }
    }

    @Test
    void theControllerBeginsAndEndsARequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController controller = context.getBean(RequestContextController.class);
            RequestBean bean = context.getBean(RequestBean.class);

            assertTrue(controller.activate());
            int first = bean.id();
            assertEquals(first, bean.id(), "one instance for the whole request");
            controller.deactivate();

            assertTrue(controller.activate());
            assertNotEquals(first, bean.id(), "a new request is a new instance");
            controller.deactivate();

            assertThrows(ContextNotActiveException.class, bean::id);
        }
    }

    @Test
    void activatingAnActiveContextReportsThatItDidNot() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController outer = context.getBean(RequestContextController.class);
            RequestContextController inner = context.getBean(RequestContextController.class);
            RequestBean bean = context.getBean(RequestBean.class);

            assertTrue(outer.activate());
            int id = bean.id();

            assertFalse(inner.activate(), "a request is being handled already");
            inner.deactivate();
            assertEquals(id, bean.id(), "the controller that did not begin it does not end it");

            outer.deactivate();
        }
    }

    @Test
    void deactivatingWithoutARequestIsAnError() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController controller = context.getBean(RequestContextController.class);
            assertThrows(ContextNotActiveException.class, controller::deactivate);
        }
    }

    @Test
    void theAdviceMakesTheMethodOneRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            OneRequestPerCall calls = context.getBean(OneRequestPerCall.class);
            RequestBean bean = context.getBean(RequestBean.class);

            int first = calls.idOfTheRequest(bean);
            int second = calls.idOfTheRequest(bean);

            assertNotEquals(first, second, "each call is its own request");
            assertThrows(ContextNotActiveException.class, bean::id, "and the request is over when it returns");
        }
    }

    @Test
    void theAdviceOnTheClassMakesEveryMethodOneRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            EveryCallIsARequest calls = context.getBean(EveryCallIsARequest.class);
            RequestBean bean = context.getBean(RequestBean.class);

            assertNotEquals(calls.idOfTheRequest(bean), calls.idOfTheRequest(bean));
            assertThrows(ContextNotActiveException.class, bean::id);
        }
    }

    @Test
    void theControllerCanBeInjected() {
        try (ApplicationContext context = ApplicationContext.run()) {
            InjectsTheController uses = context.getBean(InjectsTheController.class);
            RequestBean bean = context.getBean(RequestBean.class);

            assertNotEquals(uses.idOfTheRequest(bean), uses.idOfTheRequest(bean));
            assertThrows(ContextNotActiveException.class, bean::id);
        }
    }

    @Test
    void aDisposerRunsWhenTheRequestEnds() {
        DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);
            OwnsAConn owner = context.getBean(OwnsAConn.class);

            int connId = scope.supply(owner::connId);

            assertEquals(List.of("conn" + connId), DISPOSED);
        }
    }

    @Test
    void aRequestReachesTheThreadItsWorkIsHandedTo() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);
            RequestBean bean = context.getBean(RequestBean.class);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                int[] seen = scope.supply(() -> {
                    int here = bean.id();
                    Callable<Integer> elsewhere = bean::id;
                    try {
                        return new int[]{here, executor.submit(PropagatedContext.wrapCurrent(elsewhere)).get()};
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                assertEquals(seen[0], seen[1], "the same request, on the other thread");
            } finally {
                executor.shutdown();
            }
        }
    }

    @Test
    void theLambdaFormHoldsWhenPropagationIsByScopedValues() {
        DISPOSED.clear();
        PropagatedContextConfiguration.set(PropagatedContextConfiguration.Mode.SCOPED_VALUE);
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);
            OwnsAConn owner = context.getBean(OwnsAConn.class);

            int connId = scope.supply(owner::connId);

            assertEquals(List.of("conn" + connId), DISPOSED);
        } finally {
            PropagatedContextConfiguration.reset();
        }
    }
}
