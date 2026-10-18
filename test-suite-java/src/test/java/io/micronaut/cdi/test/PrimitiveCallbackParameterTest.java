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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Section 2.5.2.4: a producer that returns null satisfies a primitive injection point with the primitive's default
 * value, a parameter of an observer or a disposer method as well as a field.
 */
class PrimitiveCallbackParameterTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Absent {
    }

    public record Knock() {
    }

    public static class Gadget {
    }

    @Dependent
    public static class Resource {
        static final java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger destroyed = new java.util.concurrent.atomic.AtomicInteger();

        @jakarta.annotation.PostConstruct
        void start() {
            created.incrementAndGet();
        }

        @jakarta.annotation.PreDestroy
        void stop() {
            destroyed.incrementAndGet();
        }
    }

    @Dependent
    public static class Producers {
        static volatile int observed = -1;
        static volatile int disposed = -1;

        @Produces
        @Dependent
        @Absent
        Integer absent(Resource resource) {
            return null;
        }

        @Produces
        @Dependent
        Gadget gadget() {
            return new Gadget();
        }

        void on(@Observes Knock knock, @Absent int value) {
            observed = value;
        }

        void dispose(@Disposes Gadget gadget, @Absent int value) {
            disposed = value;
        }
    }

    @Dependent
    public static class Client {
        @Inject
        @Absent
        int field;
        @Inject
        Event<Knock> knocks;
        @Inject
        Instance<Gadget> gadgets;
    }

    @Test
    void aNullProducedForAPrimitiveParameterOfACallbackIsItsDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(0, context.getBean(Client.class).field);
            Client client = context.getBean(Client.class);
            client.knocks.fire(new Knock());
            assertEquals(0, Producers.observed);
            Instance.Handle<Gadget> handle = client.gadgets.getHandle();
            handle.get();
            handle.destroy();
            assertEquals(0, Producers.disposed);
        }
    }

    @Test
    void theNullProductOfACallbackParameterIsDestroyedWithTheInvocation() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            int created = Resource.created.get();
            int destroyed = Resource.destroyed.get();
            client.knocks.fire(new Knock());
            assertEquals(created + 1, Resource.created.get());
            // what the producer was injected with to produce null is released after the observer returns
            assertEquals(destroyed + 1, Resource.destroyed.get());

            Instance.Handle<Gadget> handle = client.gadgets.getHandle();
            handle.get();
            handle.destroy();
            assertEquals(created + 2, Resource.created.get());
            assertEquals(destroyed + 2, Resource.destroyed.get());
        }
    }
}
