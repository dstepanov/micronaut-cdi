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
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An interceptor that is also an alternative runs in the order of the priority it declares: the processor writes the
 * priority of an alternative into Micronaut's order, negated, and the interceptor chain is ordered by the declared
 * priority first (Jakarta Interceptors main, the fix of F9).
 */
class AlternativeInterceptorOrderTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD})
    @interface Traced {
    }

    @Traced
    @Alternative
    @jakarta.interceptor.Interceptor
    @Priority(2000)
    public static class LateAlternativeInterceptor {
        @AroundInvoke
        Object late(InvocationContext ctx) throws Exception {
            return "late(" + ctx.proceed() + ")";
        }
    }

    @Traced
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class EarlyInterceptor {
        @AroundInvoke
        Object early(InvocationContext ctx) throws Exception {
            return "early(" + ctx.proceed() + ")";
        }
    }

    @Dependent
    @Traced
    public static class TracedTarget {
        public String call() {
            return "target";
        }
    }

    @Test
    void theAlternativeInterceptorRunsByItsDeclaredPriority() {
        try (ApplicationContext context = ApplicationContext.run()) {
            // the lower priority is called first, and wraps the higher
            assertEquals("early(late(target))", context.getBean(TracedTarget.class).call());
        }
    }
}
