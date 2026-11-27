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
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lookup by name finds the beans of the name available for injection (section 11.3.2), which no interceptor is,
 * even one that declares a name.
 */
class NamedLookupOfInterceptorTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {
    }

    @Counted
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    @Named("namedLookupInterceptor")
    public static class CountingInterceptor {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Test
    void aNamedInterceptorIsNotABeanOfItsName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertTrue(manager.getBeans("namedLookupInterceptor").isEmpty(),
                () -> manager.getBeans("namedLookupInterceptor").toString());
        }
    }
}
