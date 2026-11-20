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
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The container's invocations of an observer, a producer and a disposer method of an intercepted bean are business
 * method invocations, intercepted by its method interceptors (CDI 4.1 section 7.2), whether the bean declares the
 * method or inherits it from a superclass.
 */
class ObserverInterceptionTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Test
    void anObserverMethodOfAnInterceptedBeanIsIntercepted() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Ring.class).fire(new Ring());
        }
        assertEquals(List.of("intercepted own", "heard own", "intercepted inherited", "heard inherited"),
            EVENTS.stream().filter(e -> e.endsWith("inherited") || e.endsWith("own")).toList());
    }

    @Test
    void theProducerAndDisposerMethodsOfAnInterceptedBeanAreIntercepted() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Coil> handle = lookup.select(Coil.class).getHandle();
            handle.get();
            handle.destroy();
        }
        assertEquals(List.of("intercepted wind", "intercepted unwind", "unwound"), EVENTS);
    }

    @Test
    void theDisposerMethodOfAnInterceptedNormalScopedBeanIsIntercepted() {
        EVENTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Spool> handle = lookup.select(Spool.class).getHandle();
            handle.get();
            handle.destroy();
        }
        assertEquals(List.of("intercepted spin", "intercepted unspin", "unspun"), EVENTS);
    }

    /**
     * What the reel produces and disposes of.
     */
    public static final class Spool {
    }

    /**
     * An intercepted application scoped bean producing a dependent bean and disposing of it, through methods that are
     * not public.
     */
    @ApplicationScoped
    @Traced
    public static class Reel {
        @Produces
        @Dependent
        Spool spin() {
            return new Spool();
        }

        void unspin(@Disposes Spool spool) {
            EVENTS.add("unspun");
        }
    }

    /**
     * The event observed.
     */
    public record Ring() {
    }

    /**
     * What the dispenser produces and disposes of.
     */
    public static final class Coil {
    }

    /**
     * Binds the interceptor below.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Traced {
    }

    /**
     * Records the method it intercepts.
     */
    @Interceptor
    @Traced
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Tracer {
        @AroundInvoke
        Object trace(InvocationContext context) throws Exception {
            String method = context.getMethod().getName();
            EVENTS.add("intercepted " + switch (method) {
                case "hearInherited" -> "inherited";
                case "hearOwn" -> "own";
                default -> method;
            });
            return context.proceed();
        }
    }

    /**
     * Declares the inherited observer method, and is not intercepted itself.
     */
    @Dependent
    public static class Bell {
        void hearInherited(@Observes Ring ring) {
            if (this instanceof Chime) {
                EVENTS.add("heard inherited");
            }
        }
    }

    /**
     * An intercepted application scoped subclass, with an observer method of its own.
     */
    @ApplicationScoped
    @Traced
    public static class Chime extends Bell {
        void hearOwn(@Observes @Priority(Interceptor.Priority.APPLICATION + 1) Ring ring) {
            EVENTS.add("heard own");
        }
    }

    /**
     * An intercepted bean producing and disposing.
     */
    @Dependent
    @Traced
    public static class Dispenser {
        @Produces
        Coil wind() {
            return new Coil();
        }

        void unwind(@Disposes Coil coil) {
            EVENTS.add("unwound");
        }
    }
}
