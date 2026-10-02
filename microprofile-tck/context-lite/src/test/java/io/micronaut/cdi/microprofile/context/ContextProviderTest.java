package io.micronaut.cdi.microprofile.context;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContextProviderTest {
    static final AtomicInteger IDS = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    @RequestScoped
    public static class State {
        private final int id = IDS.incrementAndGet();
        public int id() { return id; }
        @PreDestroy void destroy() { DESTROYED.incrementAndGet(); }
    }

    record Marker(String value) implements PropagatedContextElement { }

    @Test
    void propagatedRequestSharesIdentityAndRestoresWorkerContextWithoutDestroyingOwner() throws Exception {
        DESTROYED.set(0);
        try (var context = ApplicationContext.run(); var executor = Executors.newSingleThreadExecutor()) {
            var requests = context.getBean(RequestScope.class);
            var bean = context.getBean(State.class);
            requests.activate();
            int original = bean.id();
            var snapshot = requests.capture();
            executor.submit(() -> requests.run(() -> {
                int worker = bean.id();
                assertNotEquals(original, worker);
                var marker = new Marker("worker");
                PropagatedContext.getOrEmpty().plus(marker).propagate(() -> {
                    try (var ignored = snapshot.begin()) {
                        assertEquals(original, bean.id());
                        assertEquals(marker, PropagatedContext.getOrEmpty().find(Marker.class).orElseThrow());
                        try (var nested = snapshot.begin()) { assertEquals(original, bean.id()); }
                    }
                    assertEquals(worker, bean.id());
                });
            })).get(10, TimeUnit.SECONDS);
            assertEquals(1, DESTROYED.get(), "only the worker request has ended");
            assertEquals(original, bean.id());
            requests.deactivate();
            assertEquals(2, DESTROYED.get());
            assertThrows(IllegalStateException.class, snapshot::begin);
        }
    }

    @Test
    void clearedRequestIsFreshPerInvocationAndRestoredAfterFailure() {
        DESTROYED.set(0);
        try (var context = ApplicationContext.run()) {
            var requests = context.getBean(RequestScope.class);
            var bean = context.getBean(State.class);
            requests.run(() -> {
                int owner = bean.id();
                var cleared = requests.captureCleared();
                int first;
                try (var ignored = cleared.begin()) { first = bean.id(); assertNotEquals(owner, first); }
                assertEquals(owner, bean.id());
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var ignored = cleared.begin()) {
                        assertNotEquals(first, bean.id());
                        assertNotEquals(owner, bean.id());
                        throw new IllegalArgumentException("task failed");
                    }
                });
                assertEquals(owner, bean.id());
                assertEquals(2, DESTROYED.get());
            });
            assertEquals(3, DESTROYED.get());
        }
    }

    @Test
    void inactiveCaptureMasksReceivingRequestAndRestoresIt() {
        try (var context = ApplicationContext.run()) {
            var requests = context.getBean(RequestScope.class);
            var inactive = requests.capture();
            var cleared = requests.captureCleared();
            requests.run(() -> {
                assertTrue(requests.isActive());
                try (var ignored = inactive.begin()) { assertFalse(requests.isActive()); }
                try (var ignored = cleared.begin()) { assertFalse(requests.isActive()); }
                assertTrue(requests.isActive());
            });
        }
    }

    @Test
    void smallRyeBuildersUseServiceProviderAndRealCdiRequestBeans() throws Exception {
        try (var context = ApplicationContext.run()) {
            var requests = context.getBean(RequestScope.class);
            var bean = context.getBean(State.class);
            requests.activate();
            try {
                int owner = bean.id();
                var propagated = ThreadContext.builder().propagated(ThreadContext.CDI)
                    .cleared(ThreadContext.ALL_REMAINING).build();
                assertEquals(owner, propagated.contextualCallable(bean::id).call());
                var cleared = ThreadContext.builder().propagated().cleared(ThreadContext.ALL_REMAINING).build();
                assertNotEquals(owner, cleared.contextualCallable(bean::id).call());
                assertEquals(owner, bean.id());
                ManagedExecutor managed = ManagedExecutor.builder().propagated(ThreadContext.CDI)
                    .cleared(ThreadContext.ALL_REMAINING).build();
                try { assertEquals(owner, managed.supplyAsync(bean::id).get(10, TimeUnit.SECONDS)); }
                finally { managed.shutdown(); }
            } finally { requests.deactivate(); }
        }
    }

    @Test
    void clearedHandleRejectsWrongThreadAndDestroysExactlyOnce() throws Exception {
        DESTROYED.set(0);
        try (var context = ApplicationContext.run(); var worker = Executors.newSingleThreadExecutor()) {
            var requests = context.getBean(RequestScope.class);
            requests.activate();
            try {
                var handle = requests.captureCleared().begin();
                context.getBean(State.class).id();
                worker.submit(() -> assertThrows(IllegalStateException.class, handle::close))
                    .get(10, TimeUnit.SECONDS);
                assertTrue(requests.isActive());
                assertEquals(0, DESTROYED.get());
                handle.close();
                handle.close();
                assertEquals(1, DESTROYED.get());
                assertTrue(requests.isActive());
            } finally { requests.deactivate(); }
        }
    }
}
