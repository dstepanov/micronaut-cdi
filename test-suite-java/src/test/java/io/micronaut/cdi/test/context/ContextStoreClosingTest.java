/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.propagation.PropagatedContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instances a context holds of the contextuals a program handed it end with the context: one created while the
 * context was being destroyed is destroyed rather than kept where nothing destroys it again, and while the context
 * is destroyed, its instances not yet destroyed are still the ones a destruction callback finds.
 */
class ContextStoreClosingTest {

    @Test
    void anInstanceCreatedWhileTheContextEndedIsDestroyedRatherThanKept() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            CountDownLatch creating = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<String> log = new CopyOnWriteArrayList<>();
            Probe slow = new Probe("slow", log, () -> {
                creating.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, () -> { });
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Object> late;
                requests.activate();
                try {
                    Context requestContext = container.getContext(RequestScoped.class);
                    PropagatedContext request = PropagatedContext.getOrEmpty();
                    late = executor.submit(() -> {
                        try (PropagatedContext.Scope ignored = request.propagate()) {
                            return requestContext.get(slow, container.createCreationalContext(slow));
                        }
                    });
                    assertTrue(creating.await(10, TimeUnit.SECONDS));
                } finally {
                    // the request ends while the creation is under way
                    requests.deactivate();
                }
                release.countDown();
                try {
                    late.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // refused, as the context it was created for has ended
                }
                assertEquals(1, slow.created.get());
                assertEquals(1, slow.destroyed.size(), "what was created for the ended request is destroyed");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void aDestructionCallbackFindsTheInstancesNotYetDestroyed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            List<String> log = new CopyOnWriteArrayList<>();
            Probe peer = Probe.of("peer", log);
            Object[] found = new Object[1];
            requests.activate();
            Context requestContext = container.getContext(RequestScoped.class);
            Probe first = Probe.destroyedWith("first", log,
                () -> found[0] = requestContext.get(peer, container.createCreationalContext(peer)));
            Object firstInstance = requestContext.get(first, container.createCreationalContext(first));
            Object peerInstance = requestContext.get(peer, container.createCreationalContext(peer));
            requests.deactivate();
            assertEquals(List.of(firstInstance), first.destroyed);
            assertSame(peerInstance, found[0], "the peer the callback asked for is the one the context held");
            assertEquals(1, peer.created.get(), "no second peer was created for a context on its way out");
            assertEquals(List.of(peerInstance), peer.destroyed);
        }
    }
}
