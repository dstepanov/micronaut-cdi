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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.inject.BeanIdentifier;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A bean of Micronaut's own that a scope of Micronaut's holds is shared by whoever looks it up: an observer it is
 * resolved for does not own it, and the notification does not destroy it when it completes.
 */
class MicronautScopedLookupTest {

    @BeforeEach
    void clear() {
        Pooled.DESTROYED.set(0);
    }

    @Test
    void anObserverParameterHeldByAMicronautScopeOutlivesTheNotification() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Ping.class).fire(new Ping());
            container.getEvent().select(Ping.class).fire(new Ping());
            Watcher watcher = context.getBean(Watcher.class);
            assertEquals(2, watcher.seen().size());
            assertSame(watcher.seen().get(0), watcher.seen().get(1));
            assertEquals(0, Pooled.DESTROYED.get());
            // the instance Micronaut's own lookup finds in the scope: a point left to Micronaut's rules is no
            // second key in the scope
            assertSame(context.getBean(Pooled.class), watcher.seen().get(0));
        }
    }

    @Test
    void aFieldOfABeanOfTheSpecificationSharesTheInstanceTheScopeHoldsForMicronaut() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Pooled micronauts = context.getBean(Pooled.class);
            assertSame(micronauts, context.getBean(Holder.class).pooled());
        }
    }

    @Test
    void aDisposerParameterSharesTheInstanceTheScopeHoldsForMicronaut() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Pooled micronauts = context.getBean(Pooled.class);
            Instance.Handle<Ticket> handle = context.getBean(CdiBeanContainer.class).createInstance()
                .select(Ticket.class).getHandle();
            handle.get();
            handle.destroy();
            Tickets tickets = context.getBean(Tickets.class);
            assertEquals(1, tickets.disposedWith().size());
            assertSame(micronauts, tickets.disposedWith().get(0));
        }
    }

    @Scope
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Pool {
    }

    @Singleton
    public static class PoolScope extends AbstractConcurrentCustomScope<Pool> {
        private final Map<BeanIdentifier, CreatedBean<?>> beans = new ConcurrentHashMap<>();

        public PoolScope() {
            super(Pool.class);
        }

        @Override
        protected Map<BeanIdentifier, CreatedBean<?>> getScopeMap(boolean forCreation) {
            return beans;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void close() {
            destroyScope(beans);
        }
    }

    @Bean
    @Pool
    public static class Pooled {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    public record Ping() {
    }

    public record Ticket() {
    }

    @ApplicationScoped
    public static class Holder {
        @Inject
        Pooled pooled;

        Pooled pooled() {
            return pooled;
        }
    }

    @ApplicationScoped
    public static class Tickets {
        private final List<Pooled> disposedWith = new CopyOnWriteArrayList<>();

        @Produces
        @Dependent
        Ticket ticket() {
            return new Ticket();
        }

        void dispose(@Disposes Ticket ticket, Pooled pooled) {
            disposedWith.add(pooled);
        }

        List<Pooled> disposedWith() {
            return disposedWith;
        }
    }

    @ApplicationScoped
    public static class Watcher {
        private final List<Pooled> seen = new CopyOnWriteArrayList<>();

        void on(@Observes Ping ping, Pooled pooled) {
            seen.add(pooled);
        }

        List<Pooled> seen() {
            return seen;
        }
    }
}
