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
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dependent bean bound to an interceptor, at its class or at a method, declares producers of beans of a normal
 * scope like any other bean: the bean declaring the producer is found through its intercepted definition.
 */
class InterceptedFactoryTest {

    /**
     * Binds the interceptor below.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audited {
    }

    /**
     * Passes every call through.
     */
    @Interceptor
    @Audited
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Auditor {
        @AroundInvoke
        Object audit(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    /**
     * What the factories produce, in the application scope.
     */
    public static class Report {
        private String by = "";

        Report by(String name) {
            this.by = name;
            return this;
        }

        public String by() {
            return by;
        }
    }

    /**
     * What the other factory produces.
     */
    public static class Memo extends Report {
    }

    /**
     * A dependent bean bound to the interceptor at its class.
     */
    @Dependent
    @Audited
    public static class AuditedFactory {
        @Produces
        @ApplicationScoped
        Report report() {
            return new Report().by("class binding");
        }

        @Produces
        Note note() {
            return new Note("plain");
        }
    }

    /**
     * A dependent bean bound to the interceptor at a method.
     */
    @Dependent
    public static class MethodAuditedFactory {
        @Audited
        public void audited() {
        }

        @Produces
        @ApplicationScoped
        Memo memo() {
            return (Memo) new Memo().by("method binding");
        }
    }

    @Test
    void aDependentProductOfAnInterceptedDependentBeanIsCreated() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("plain", container.createInstance().select(Note.class).get().text());
        }
    }

    /**
     * What the audited factory produces in the dependent pseudo-scope.
     *
     * @param text The text
     */
    public record Note(String text) {
    }

    @Test
    @Disabled("Micronaut Core does not recognise the generated subclass of an intercepted bean as the factory of the "
        + "product it creates, and resolves the factory through the product's scope "
        + "(DefaultBeanContext.findInjectionPointDeclaredScope, see CONFORMANCE.md, Open points in Micronaut Core)")
    void aNormalScopedProductOfAnInterceptedDependentBeanIsCreated() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("class binding",
                container.createInstance().select(Report.class, jakarta.enterprise.inject.Default.Literal.INSTANCE)
                    .stream().filter(report -> !(report instanceof Memo)).findFirst().orElseThrow().by());
            assertEquals("method binding", container.createInstance().select(Memo.class).get().by());
        }
    }
}
