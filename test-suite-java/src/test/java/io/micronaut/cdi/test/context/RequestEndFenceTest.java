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

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.control.RequestContextController;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A creation that found the request before it ended, and reaches Micronaut's scope only after the request's beans
 * were destroyed, does not leave its bean in the ended request: the bean is closed and the creation is told the
 * request ended. Micronaut's scope asks the creation for its identifier after it found the request's map and before
 * it announces the creation, which is where the creation here waits for the request to end.
 */
class RequestEndFenceTest {

    @Test
    void aBeanPublishedAfterItsRequestEndedIsClosedAndRefused() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestScope scope = context.getBean(RequestScope.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            Late late = new Late(paused, resume, closed);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Object> created;
                requests.activate();
                try {
                    PropagatedContext request = PropagatedContext.getOrEmpty();
                    created = executor.submit(() -> {
                        try (PropagatedContext.Scope ignored = request.propagate()) {
                            return scope.getOrCreate(late);
                        }
                    });
                    assertTrue(paused.await(10, TimeUnit.SECONDS));
                } finally {
                    // the request ends while the creation waits between finding the map and announcing itself
                    requests.deactivate();
                }
                resume.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> created.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ContextNotActiveException.class, failure.getCause());
                assertEquals(1, closed.get(), "the bean created for the ended request is closed, once");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * A creation that waits, the first time it is asked for its identifier, until it is told to go on.
     */
    private static final class Late implements BeanCreationContext<Object> {

        private static final BeanIdentifier ID = BeanIdentifier.of("late");

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static final BeanDefinition<Object> DEFINITION =
            (BeanDefinition) RuntimeBeanDefinition.of(Object.class, Object::new);

        private final CountDownLatch paused;
        private final CountDownLatch resume;
        private final AtomicInteger closed;
        private final AtomicBoolean first = new AtomicBoolean(true);

        Late(CountDownLatch paused, CountDownLatch resume, AtomicInteger closed) {
            this.paused = paused;
            this.resume = resume;
            this.closed = closed;
        }

        @Override
        public BeanDefinition<Object> definition() {
            return DEFINITION;
        }

        @Override
        public BeanIdentifier id() {
            if (first.getAndSet(false)) {
                paused.countDown();
                try {
                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ID;
        }

        @Override
        public CreatedBean<Object> create() {
            Object bean = new Object();
            return new CreatedBean<>() {
                @Override
                public BeanDefinition<Object> definition() {
                    return DEFINITION;
                }

                @Override
                public Object bean() {
                    return bean;
                }

                @Override
                public BeanIdentifier id() {
                    return ID;
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }
}
