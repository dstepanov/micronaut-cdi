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
package org.example.cdi.extension;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBean;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.util.AnnotationLiteral;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the portable discovery events tell an extension: the bean of a producer is a {@code ProcessBean} of the type
 * it produces (section 11.5.11), what an observer of {@code ProcessAnnotatedType} added is read by the events that
 * follow, a name among it included (11.5.6), an interceptor is a discovered type, and an annotation
 * {@code BeforeBeanDiscovery} made a qualifier qualifies the beans of the classes written with it (11.5.1).
 */
class PortableDiscoveryEventsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Marked {
        final class Literal extends AnnotationLiteral<Marked> implements Marked {
        }
    }

    public static class Thing {
    }

    @Dependent
    public static class ThingFactory {
        @Produces
        Thing thing() {
            return new Thing();
        }
    }

    @ApplicationScoped
    public static class Plain {
    }

    @Marked
    @ApplicationScoped
    public static class MarkedBean {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Watched {
    }

    @Watched
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class WatchingInterceptor {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    public static class Watching implements Extension {
        final AtomicBoolean producerSeen = new AtomicBoolean();
        final AtomicBoolean addedSeen = new AtomicBoolean();
        final AtomicBoolean interceptorSeen = new AtomicBoolean();

        void qualifier(@Observes BeforeBeanDiscovery event) {
            event.addQualifier(Marked.class);
        }

        void add(@Observes ProcessAnnotatedType<Plain> event) {
            event.configureAnnotatedType().add(new Marked.Literal()).add(NamedLiteral.of("renamedPlain"));
        }

        void interceptor(@Observes ProcessAnnotatedType<WatchingInterceptor> event) {
            interceptorSeen.set(true);
        }

        void target(@Observes ProcessInjectionTarget<Plain> event) {
            addedSeen.set(event.getAnnotatedType().isAnnotationPresent(Marked.class));
        }

        void produced(@Observes ProcessBean<Thing> event) {
            producerSeen.set(true);
        }
    }

    @Test
    void theDiscoveryEventsDescribeWhatTheExtensionsSee() {
        Watching watching = new Watching();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(ThingFactory.class, Plain.class, MarkedBean.class, WatchingInterceptor.class)
            .enableInterceptors(WatchingInterceptor.class)
            .addExtensions(watching).initialize()) {
            assertTrue(watching.producerSeen.get(), "ProcessBean<Thing> for the producer");
            assertTrue(watching.addedSeen.get(), "what ProcessAnnotatedType added, in ProcessInjectionTarget");
            assertTrue(watching.interceptorSeen.get(), "ProcessAnnotatedType for the interceptor");
            assertEquals(1, container.getBeanManager().getBeans("renamedPlain").size(), "the name added");
            assertEquals("renamedPlain", container.getBeanManager().resolve(container.getBeanManager()
                .getBeans(Plain.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)).getName());
            assertEquals(1, container.getBeanManager().getBeans(MarkedBean.class, new Marked.Literal()).size(),
                "qualified by an annotation made a qualifier");
        }
    }

    static final class NamedLiteral extends AnnotationLiteral<Named> implements Named {
        private final String value;

        private NamedLiteral(String value) {
            this.value = value;
        }

        static NamedLiteral of(String value) {
            return new NamedLiteral(value);
        }

        @Override
        public String value() {
            return value;
        }
    }
}
