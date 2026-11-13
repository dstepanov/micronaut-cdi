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

import io.micronaut.aop.Around;
import io.micronaut.aop.InterceptedProxy;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Type;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BeanContainer.unwrapClientProxy} unwraps the client proxy of a bean in a normal scope, and returns any other
 * reference as it is - a proxy Micronaut's own advice generated among them (CDI 5.0, section 11.3).
 */
class UnwrapClientProxyTest {

    @Test
    void aProxyOfMicronautsOwnAdviceIsNoClientProxy() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            Advised advised = context.getBean(Advised.class);
            assertTrue(advised instanceof InterceptedProxy<?>, "the advice proxies its target");
            assertSame(advised, container.unwrapClientProxy(advised));

            Scoped scoped = context.getBean(Scoped.class);
            Scoped unwrapped = container.unwrapClientProxy(scoped);
            assertNotSame(scoped, unwrapped, "the client proxy of a normal scoped bean is unwrapped");
        }
    }

    /**
     * Micronaut advice that proxies its target.
     */
    @Around(proxyTarget = true)
    @Type(PassingInterceptor.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Passing {
    }

    /**
     * Hands every invocation on.
     */
    @Singleton
    public static class PassingInterceptor implements MethodInterceptor<Object, Object> {
        @Override
        public Object intercept(MethodInvocationContext<Object, Object> context) {
            return context.proceed();
        }
    }

    /**
     * A singleton behind Micronaut's advice.
     */
    @Singleton
    @Passing
    public static class Advised {
        public String hello() {
            return "hello";
        }
    }

    /**
     * A bean in a normal scope.
     */
    @ApplicationScoped
    public static class Scoped {
        public String hello() {
            return "hello";
        }
    }
}
