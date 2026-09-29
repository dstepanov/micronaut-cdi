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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An interceptor of a request scoped bean keeps its state per instance of that bean, not per client proxy: the
 * one proxy of a normal scoped bean stands in front of an instance per request, and what an interceptor
 * counted for one request is not what it counts for the next.
 */
class RequestScopedInterceptorStateTest {

    @Test
    void anInterceptorOfARequestScopedBeanStartsAgainEachRequest() {
        Tallied.COUNTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController controller = context.getBean(RequestContextController.class);
            Ledger ledger = container.createInstance().select(Ledger.class).get();

            controller.activate();
            try {
                ledger.record();
                ledger.record();
            } finally {
                controller.deactivate();
            }

            controller.activate();
            try {
                ledger.record();
            } finally {
                controller.deactivate();
            }

            // two entries for the first request's instance, then a fresh interceptor for the second's
            assertEquals(List.of(1, 2, 1), Tallied.COUNTS);
        }
    }

    /**
     * Binds the interceptor that counts.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Counted {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Counted> implements Counted {
        }
    }

    /**
     * Counts what it saw of the one object it interposes on.
     */
    @Interceptor
    @Counted
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Tallied {

        /**
         * What each instance of this interceptor counted, in the order the interceptions happened.
         */
        public static final List<Integer> COUNTS = new CopyOnWriteArrayList<>();

        private final AtomicInteger seen = new AtomicInteger();

        @AroundInvoke
        Object count(InvocationContext context) throws Exception {
            Object result = context.proceed();
            COUNTS.add(seen.incrementAndGet());
            return result;
        }
    }

    /**
     * A request scoped bean, so that one client proxy fronts one instance per request.
     */
    @RequestScoped
    @Counted
    public static class Ledger {

        public String record() {
            return "recorded";
        }
    }
}
