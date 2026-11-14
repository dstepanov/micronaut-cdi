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
package io.micronaut.cdi.el;

import io.micronaut.aop.Around;
import io.micronaut.aop.InterceptedProxy;
import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.ApplicationContext;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.el.ELAwareBeanManager;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A bean behind a proxy of Micronaut's own advice - one that targets the bean, which a client proxy of a normal
 * scope is not - resolves to that proxy: the name answers the contextual reference, and the advice applies to what an
 * expression invokes on it.
 */
class ProxyTargetAdviceExpressionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Around(proxyTarget = true)
    public @interface Loud {
    }

    @Singleton
    @InterceptorBean(Loud.class)
    public static class LoudInterceptor implements MethodInterceptor<Object, Object> {
        @Override
        public Object intercept(MethodInvocationContext<Object, Object> context) {
            Object result = context.proceed();
            return result instanceof String text ? text.toUpperCase() : result;
        }
    }

    @Named("crier")
    @Dependent
    @Loud
    public static class Crier {
        public String getCall() {
            return "hear ye";
        }
    }

    @Test
    void theResolverAnswersTheProxyOfMicronautsAdviceAsItIs() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ELAwareBeanManager manager = (ELAwareBeanManager) context.getBean(BeanManager.class);
            ELContext el = new StandardELContext(ExpressionFactory.newInstance());
            Object crier = manager.getELResolver().getValue(el, null, "crier");
            assertInstanceOf(InterceptedProxy.class, crier);
            assertEquals("HEAR YE", ((Crier) crier).getCall());
        }
    }

    @Test
    void anExpressionReadsTheBeanThroughTheAdvice() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ELAwareBeanManager manager = (ELAwareBeanManager) context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            assertEquals("HEAR YE", factory.createValueExpression(el, "${crier.call}", String.class).getValue(el));
        }
    }
}
