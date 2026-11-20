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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Typed;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean whose {@code Typed} leaves its own class out of its bean types, and which inherits an around-invoke method
 * from its superclass, deploys, and the method interposes on its observer method as on any business method.
 */
class TypedAwayInterceptorClassTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * The event observed.
     */
    public record Tick() {
    }

    /**
     * Declares the around-invoke method.
     */
    @Dependent
    public static class Timed {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            EVENTS.add("around " + context.getMethod().getName());
            return context.proceed();
        }
    }

    /**
     * A bean of no type, inheriting the around-invoke method and observing.
     */
    @Dependent
    @Typed
    public static class Clock extends Timed {
        void tick(@Observes Tick tick) {
            EVENTS.add("ticked");
        }
    }

    @Test
    void theInheritedAroundInvokeInterposesOnTheObserverOfABeanOfNoType() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Tick.class).fire(new Tick());
        }
        assertEquals(List.of("around tick", "ticked"), EVENTS);
    }
}
