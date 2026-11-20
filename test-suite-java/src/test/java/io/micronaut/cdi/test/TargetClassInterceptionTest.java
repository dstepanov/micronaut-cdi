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
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An around-invoke method a bean class declares itself, or inherits from a superclass, interposes on the container's
 * calls of its observer, producer and disposer methods as on any business method (CDI 4.1 section 7.2; Jakarta
 * Interceptors 2.2 section 2.4), for a bean of a normal scope as for a dependent one.
 */
class TargetClassInterceptionTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Test
    void theOwnAroundInvokeOfANormalScopedBeanInterposesOnItsObserverMethods() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Chime.class).fire(new Chime());
        }
        assertEquals(List.of("around hearOwn", "heard own", "around hearInherited", "heard inherited",
            "around hearWithInheritedAround", "heard with inherited around"), EVENTS);
    }

    @Test
    void theOwnAroundInvokeOfANormalScopedBeanInterposesOnItsProducerAndDisposer() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Coin> handle = lookup.select(Coin.class).getHandle();
            handle.get();
            handle.destroy();
        }
        assertEquals(List.of("around mint", "around melt", "melted"), EVENTS);
    }

    /**
     * The event observed.
     */
    public record Chime() {
    }

    /**
     * What the mint produces and disposes of.
     */
    public static final class Coin {
    }

    /**
     * Declares an observer method its subclass inherits.
     */
    public static class Bell {
        void hearInherited(@Observes @Priority(Interceptor.Priority.APPLICATION + 2) Chime chime) {
            if (this instanceof Tower) {
                EVENTS.add("heard inherited");
            }
        }
    }

    /**
     * An application scoped bean with an around-invoke method of its own.
     */
    @ApplicationScoped
    public static class Tower extends Bell {
        void hearOwn(@Observes @Priority(Interceptor.Priority.APPLICATION + 1) Chime chime) {
            EVENTS.add("heard own");
        }

        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            EVENTS.add("around " + context.getMethod().getName());
            return context.proceed();
        }
    }

    /**
     * Declares an around-invoke method its subclass inherits.
     */
    public static class Watched {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            EVENTS.add("around " + context.getMethod().getName());
            return context.proceed();
        }
    }

    /**
     * An application scoped bean that inherits its around-invoke method.
     */
    @ApplicationScoped
    public static class Steeple extends Watched {
        void hearWithInheritedAround(@Observes @Priority(Interceptor.Priority.APPLICATION + 3) Chime chime) {
            EVENTS.add("heard with inherited around");
        }
    }

    /**
     * An application scoped bean with an around-invoke method of its own, producing and disposing through methods that
     * are not public.
     */
    @ApplicationScoped
    public static class Mint {
        @Produces
        @Dependent
        Coin mint() {
            return new Coin();
        }

        void melt(@Disposes Coin coin) {
            EVENTS.add("melted");
        }

        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            EVENTS.add("around " + context.getMethod().getName());
            return context.proceed();
        }
    }
}
