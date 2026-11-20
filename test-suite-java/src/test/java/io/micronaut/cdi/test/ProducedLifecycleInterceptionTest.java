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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.AroundConstruct;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A class level interceptor binding of a bean class binds the lifecycle callback interceptors of that class's
 * instances, not of what its producer methods produce: a produced object is no instance of a managed bean, and the
 * producer method itself is a business method, intercepted around its invocation (CDI 4.1 sections 7.2 and 9; Jakarta
 * Interceptors 2.2).
 */
class ProducedLifecycleInterceptionTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Test
    @Disabled("The Jakarta Interceptors processor writes the interception of a produced bean, which shadows the one "
        + "the factory class declares for itself, only where the producer or the produced type declares interception "
        + "of its own, so the product inherits the factory's (see CONFORMANCE.md, Known limitations)")
    void theLifecycleInterceptorsOfTheDeclaringClassDoNotRunOnItsProduct() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Part> handle = lookup.select(Part.class).getHandle();
            handle.get();
            handle.destroy();
        }
        // the calls of name() the interceptor itself makes are business method invocations too, and not the point
        EVENTS.removeIf(event -> event.equals("around invoke name"));
        assertEquals(List.of("around construct Assembly", "post construct Assembly", "around invoke make",
            "pre destroy Assembly"), EVENTS);
    }

    /**
     * Binds the interceptor below.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Watched {
    }

    /**
     * Records every kind of interception it performs.
     */
    @Interceptor
    @Watched
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Watcher {

        @AroundConstruct
        Object construct(InvocationContext context) throws Exception {
            Object constructed = context.proceed();
            EVENTS.add("around construct " + named(context.getTarget()));
            return constructed;
        }

        @PostConstruct
        void postConstruct(InvocationContext context) throws Exception {
            EVENTS.add("post construct " + named(context.getTarget()));
            context.proceed();
        }

        @PreDestroy
        void preDestroy(InvocationContext context) throws Exception {
            EVENTS.add("pre destroy " + named(context.getTarget()));
            context.proceed();
        }

        @AroundInvoke
        Object invoke(InvocationContext context) throws Exception {
            EVENTS.add("around invoke " + context.getMethod().getName());
            return context.proceed();
        }

        private static String named(Object target) {
            return target instanceof Named named ? named.name() : String.valueOf(target);
        }
    }

    /**
     * What the interceptor can tell apart.
     */
    public interface Named {
        String name();
    }

    /**
     * What the assembly produces.
     */
    public static final class Part implements Named {
        @Override
        public String name() {
            return "Part";
        }
    }

    /**
     * A dependent bean bound to the interceptor at its class, producing a dependent part.
     */
    @Dependent
    @Watched
    public static class Assembly implements Named {

        @Produces
        @Dependent
        Part make() {
            return new Part();
        }

        @Override
        public String name() {
            return "Assembly";
        }
    }
}
