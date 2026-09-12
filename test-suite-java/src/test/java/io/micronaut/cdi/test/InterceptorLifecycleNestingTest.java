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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An interceptor class and the bean it interposes on may both carry the same kind of interceptor method, and the
 * order the two run in is what Jakarta Interceptors section 2.5 fixes.
 *
 * <p>An interceptor method of an interceptor class is invoked around the one the target class declares itself, so
 * that the target's own is innermost; a lifecycle callback of an interceptor class wraps the target's own callback
 * the same way. A target class that inherits an interceptor method from a superclass has the superclass's invoked
 * first, and an override is invoked once rather than twice.</p>
 *
 * <p>What records the order is the bean itself, so that the assertions read what ran rather than what was
 * resolved.</p>
 */
class InterceptorLifecycleNestingTest {

    @BeforeEach
    void clear() {
        Ledger.EVENTS.clear();
    }

    @Test
    void anInterceptorClassWrapsTheTargetClassOwnCallbacks() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Ledger> lookup = container.createInstance().select(Ledger.class);
            Instance.Handle<Ledger> handle = lookup.getHandle();
            handle.get();
            // the interceptor's @AroundConstruct is around the constructor, and its @PostConstruct around the
            // target's own
            assertEquals(List.of("Auditor.aroundConstruct", "Ledger.<init>",
                "Auditor.postConstruct", "Ledger.postConstruct"), Ledger.EVENTS);

            Ledger.EVENTS.clear();
            handle.destroy();
            assertEquals(List.of("Auditor.preDestroy", "Ledger.preDestroy"), Ledger.EVENTS);
        }
    }

    @Test
    void aTargetClassOwnAroundInvokeIsInnermostAndItsSuperclassRunsFirst() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Ledger> lookup = container.createInstance().select(Ledger.class);
            Ledger ledger = lookup.get();
            Ledger.EVENTS.clear();

            assertEquals("recorded", ledger.record());

            assertEquals(List.of("Auditor.aroundInvoke", "AbstractLedger.aroundInvoke",
                "Ledger.aroundInvoke", "Ledger.record"), Ledger.EVENTS);
        }
    }

    /**
     * Binds the auditor.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audited {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Audited> implements Audited {
        }
    }

    /**
     * An interceptor class that carries every kind of interceptor method.
     */
    @Interceptor
    @Audited
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Auditor {

        /**
         * @param context The invocation
         * @return Nothing; a construction returns no value
         * @throws Exception What the construction threw
         */
        @AroundConstruct
        Object aroundConstruct(InvocationContext context) throws Exception {
            Ledger.EVENTS.add("Auditor.aroundConstruct");
            return context.proceed();
        }

        /**
         * @param context The callback
         * @return Nothing
         * @throws Exception What the callback threw
         */
        @PostConstruct
        Object postConstruct(InvocationContext context) throws Exception {
            Ledger.EVENTS.add("Auditor.postConstruct");
            return context.proceed();
        }

        /**
         * @param context The callback
         * @return Nothing
         * @throws Exception What the callback threw
         */
        @PreDestroy
        Object preDestroy(InvocationContext context) throws Exception {
            Ledger.EVENTS.add("Auditor.preDestroy");
            return context.proceed();
        }

        /**
         * @param context The invocation
         * @return What the invocation returned
         * @throws Exception What the invocation threw
         */
        @AroundInvoke
        Object aroundInvoke(InvocationContext context) throws Exception {
            Ledger.EVENTS.add("Auditor.aroundInvoke");
            return context.proceed();
        }
    }

    /**
     * The superclass of the target, with an interceptor method of its own.
     */
    public abstract static class AbstractLedger {

        /**
         * @param context The invocation
         * @return What the invocation returned
         * @throws Exception What the invocation threw
         */
        @AroundInvoke
        Object aroundInvoke(InvocationContext context) throws Exception {
            Ledger.EVENTS.add("AbstractLedger.aroundInvoke");
            return context.proceed();
        }
    }

    /**
     * The target class, which declares interceptor methods of its own beside the ones it is bound to.
     */
    @Dependent
    @Audited
    public static class Ledger extends AbstractLedger {

        /**
         * What ran, in the order it ran.
         */
        public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

        public Ledger() {
            EVENTS.add("Ledger.<init>");
        }

        @PostConstruct
        void started() {
            EVENTS.add("Ledger.postConstruct");
        }

        @PreDestroy
        void finished() {
            EVENTS.add("Ledger.preDestroy");
        }

        /**
         * @param context The invocation
         * @return What the invocation returned
         * @throws Exception What the invocation threw
         */
        @AroundInvoke
        Object ownAroundInvoke(InvocationContext context) throws Exception {
            EVENTS.add("Ledger.aroundInvoke");
            return context.proceed();
        }

        public String record() {
            EVENTS.add("Ledger.record");
            return "recorded";
        }
    }
}
