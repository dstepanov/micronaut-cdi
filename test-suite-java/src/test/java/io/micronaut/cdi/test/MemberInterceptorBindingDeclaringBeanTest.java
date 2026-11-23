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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dependent bean carrying an interceptor binding that has a member, which no interceptor is bound to, declares
 * producers like any other bean (CDI 4.1 sections 3.2, 3.3 and 6.4.2). Reproduces the differential fuzzer's run 3
 * seeds 46, 180, 200, 251, 368 and 441 (a binding at an unrelated method breaks a normal-scoped product).
 */
class MemberInterceptorBindingDeclaringBeanTest {

    /**
     * An interceptor binding with a member, no interceptor is bound to.
     */
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Tagged {
        String value();

        @Nonbinding String note() default "";
    }

    /**
     * Produced in the application scope by a field.
     */
    public static class Stamp {
        public String id() {
            return "stamp";
        }
    }

    /**
     * Produced in the application scope by a method.
     */
    public static class Seal {
        public String id() {
            return "seal";
        }
    }

    /**
     * Bound at a method that produces nothing, and declares producers of beans of the application scope.
     */
    @Dependent
    public static class Desk {
        @Tagged(value = "a", note = "x")
        public String call(String arg) {
            return arg;
        }

        @Produces
        @ApplicationScoped
        Stamp stamp = new Stamp();

        @Produces
        @ApplicationScoped
        Seal seal() {
            return new Seal();
        }
    }

    @Test
    @Disabled("Micronaut Core does not recognise the generated subclass of an intercepted bean as the factory of the "
        + "product it creates (DefaultBeanContext.isFactoryOf, see CONFORMANCE.md, Open points in Micronaut Core)")
    void aBindingAtAnotherMethodLeavesTheProducerFieldOfANormalScopedBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("stamp", container.createInstance().select(Stamp.class).get().id());
        }
    }

    @Test
    @Disabled("Micronaut Core does not recognise the generated subclass of an intercepted bean as the factory of the "
        + "product it creates (DefaultBeanContext.isFactoryOf, see CONFORMANCE.md, Open points in Micronaut Core)")
    void aBindingAtAnotherMethodLeavesTheProducerMethodOfANormalScopedBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("seal", container.createInstance().select(Seal.class).get().id());
        }
    }
}
