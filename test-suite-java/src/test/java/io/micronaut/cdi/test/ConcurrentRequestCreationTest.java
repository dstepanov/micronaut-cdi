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
package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two requests create their own instance of a request scoped bean at the same time.
 *
 * <p>Each request holds its own instances, so the creation of a bean in one request has nothing to wait for in
 * another. Under a lock shared by every request, the second creation could not begin until the first had finished,
 * and the first waits, in its {@code @PostConstruct}, for the second to have begun: it timed out.</p>
 */
class ConcurrentRequestCreationTest {

    @Test
    void requestsCreateTheSameBeanWithoutWaitingForEachOther() throws Exception {
        Slow.entered = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ApplicationContext context = ApplicationContext.run()) {
            List<Future<String>> requests = List.of(
                executor.submit(() -> inRequest(context)),
                executor.submit(() -> inRequest(context)));
            for (Future<String> request : requests) {
                assertEquals("created", request.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static String inRequest(ApplicationContext context) {
        RequestContextController controller = context.getBean(RequestContextController.class);
        controller.activate();
        try {
            return context.getBean(Slow.class).state();
        } finally {
            controller.deactivate();
        }
    }

    /**
     * Created once in each request, and only once the other request has begun creating its own.
     */
    @RequestScoped
    public static class Slow {

        static volatile CountDownLatch entered = new CountDownLatch(2);

        private String state = "pending";

        @PostConstruct
        void init() throws InterruptedException {
            CountDownLatch latch = entered;
            latch.countDown();
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The other request did not begin creating its instance");
            }
            state = "created";
        }

        public String state() {
            return state;
        }
    }
}
