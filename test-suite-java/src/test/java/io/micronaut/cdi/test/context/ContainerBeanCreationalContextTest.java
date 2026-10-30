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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creational context a bean of the container is asked for with is released once, as the context destroys the
 * bean's instance (section 6.2), however many times the bean was asked for meanwhile.
 */
class ContainerBeanCreationalContextTest {

    @Test
    void aRepeatedLookupReleasesNothingAndTheEndOfTheContextReleasesOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            Counting<Ticket> creationalContext = new Counting<>();
            requests.activate();
            try {
                AlterableContext requestContext = (AlterableContext) container.getContext(RequestScoped.class);
                Bean<Ticket> bean = ticketBean(container);
                Ticket first = requestContext.get(bean, creationalContext);
                assertSame(first, requestContext.get(bean, creationalContext));
                assertEquals(0, creationalContext.released.get(), "released while the instance is in use");
            } finally {
                requests.deactivate();
            }
            assertEquals(1, creationalContext.released.get(), "released once, as the request ended");
        }
    }

    @Test
    void destroyingTheBeanReleasesItsCreationalContextOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            Counting<Ticket> creationalContext = new Counting<>();
            requests.activate();
            try {
                AlterableContext requestContext = (AlterableContext) container.getContext(RequestScoped.class);
                Bean<Ticket> bean = ticketBean(container);
                requestContext.get(bean, creationalContext);
                requestContext.get(bean, creationalContext);
                requestContext.destroy(bean);
                assertEquals(1, creationalContext.released.get());
            } finally {
                requests.deactivate();
            }
            assertEquals(1, creationalContext.released.get(), "not released a second time as the request ended");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedReleaseIsReportedFirstAndTheBeanIsDestroyedAllTheSame() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            IllegalStateException releaseFailure = new IllegalStateException("the release failed");
            Counting<FailingTicket> creationalContext = new Counting<>(releaseFailure);
            requests.activate();
            try {
                AlterableContext requestContext = (AlterableContext) container.getContext(RequestScoped.class);
                Bean<FailingTicket> bean = (Bean<FailingTicket>) container.resolve(
                    container.getBeans(FailingTicket.class));
                requestContext.get(bean, creationalContext);
                FailingTicket.DESTROYED.set(false);
                RuntimeException thrown = assertThrows(RuntimeException.class, () -> requestContext.destroy(bean));
                // the failure of the bean's own destruction Micronaut's scope reports itself, and does not throw
                assertSame(releaseFailure, thrown, "the first failure is the one reported");
                assertTrue(FailingTicket.DESTROYED.get(), "the bean was destroyed though the release failed");
            } finally {
                requests.deactivate();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Bean<Ticket> ticketBean(CdiBeanContainer container) {
        return (Bean<Ticket>) container.resolve(container.getBeans(Ticket.class));
    }

    /**
     * A creational context that counts its releases.
     *
     * @param <T> The type of the instance
     */
    static final class Counting<T> implements CreationalContext<T> {

        final AtomicInteger released = new AtomicInteger();
        private final RuntimeException failure;

        Counting() {
            this(null);
        }

        Counting(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void push(T incompleteInstance) {
        }

        @Override
        public void release() {
            released.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * A bean of the request whose destruction fails.
     */
    @RequestScoped
    public static class FailingTicket {

        static final AtomicBoolean DESTROYED = new AtomicBoolean();

        int number() {
            return 2;
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.set(true);
            throw new IllegalStateException("the destruction failed");
        }
    }

    /**
     * A bean of the request.
     */
    @RequestScoped
    public static class Ticket {

        int number() {
            return 1;
        }
    }
}
