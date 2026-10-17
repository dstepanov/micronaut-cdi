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
package io.micronaut.cdi.test.qualifier;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two interceptor binding types that are each annotated with the other, and so each a binding of the other's
 * (section 3.2 of Jakarta Interceptors): reading the non-binding members of one reads the interface, whose
 * annotations name the other, whose annotations name the first. The compilation has to end, and the binding has to
 * reach the interceptor of the other.
 */
class MutuallyAnnotatedBindingTypesTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Downhill
    public @interface Uphill {
        @Nonbinding String grade() default "";
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Uphill
    public @interface Downhill {
        @Nonbinding String grade() default "";
    }

    @Downhill
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Brake {
        @AroundInvoke
        Object brake(InvocationContext context) throws Exception {
            return "braked " + context.proceed();
        }
    }

    @Dependent
    public static class Bike {
        @Uphill(grade = "steep")
        public String ride() {
            return "ride";
        }
    }

    @Test
    void bindingTypesThatNameEachOtherCompileAndBind() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("braked ride", CDI.current().select(Bike.class).get().ride());
        }
    }
}
