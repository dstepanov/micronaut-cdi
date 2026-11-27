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
package io.micronaut.cdi.test.bce;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A context an extension registers for the request scope stands beside the container's own (CDI 5.0, section 6.4):
 * while the registered one is active and the container's is not, the registered one serves the beans of the scope,
 * a bean injected through its client proxy included.
 */
class ActiveRegisteredRequestContextTest {

    private static final String SOURCE = """
        package served;

        @jakarta.enterprise.context.RequestScoped
        public class Visit {

            private int count;

            public int visit() {
                return ++count;
            }

            @jakarta.enterprise.context.ApplicationScoped
            public static class Holder {
                @jakarta.inject.Inject
                Visit visit;

                public int visit() {
                    return visit.visit();
                }
            }
        }
        """;

    @Test
    void anActiveRegisteredRequestContextServesTheScopeBesideTheContainersInactiveOne() throws Exception {
        ActiveContext.INSTANCES.clear();
        try (ApplicationContext context = ExtensionDeployment.start(RegisteringExtension.class, "served.Visit",
            SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Class<?> visitClass = context.getClassLoader().loadClass("served.Visit");
            Method visit = visitClass.getMethod("visit");
            assertSame(ActiveContext.class, container.getContext(RequestScoped.class).getClass());
            Object reference = container.createInstance().select(visitClass).get();
            assertEquals(1, visit.invoke(reference));
            assertEquals(2, visit.invoke(reference), "the registered context holds one instance");
            Class<?> holderClass = context.getClassLoader().loadClass("served.Visit$Holder");
            Object holder = container.createInstance().select(holderClass).get();
            assertEquals(3, holderClass.getMethod("visit").invoke(holder), "injected through its client proxy");
            assertEquals(1, ActiveContext.INSTANCES.size());
        }
    }

    @Test
    void anActiveRegisteredRequestContextServesASyntheticBeanOfTheScope() {
        ActiveContext.INSTANCES.clear();
        try (ApplicationContext context = ExtensionDeployment.start(RegisteringWithSyntheticExtension.class,
            "served.Visit", SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            // a synthetic bean of the request scope is held by the context that serves the scope, as a compiled one
            // is: the container's own request context is not active
            Counter counter = container.createInstance().select(Counter.class).get();
            assertEquals(1, counter.next());
            assertSame(counter, container.createInstance().select(Counter.class).get());
            assertEquals(1, ActiveContext.INSTANCES.size());
        }
    }

    /**
     * Registers a context for the request scope that is always active, and a synthetic bean of the scope.
     */
    public static final class RegisteringWithSyntheticExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(RequestScoped.class, ActiveContext.class);
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Counter.class).type(Counter.class).scope(RequestScoped.class)
                .createWith(CounterCreator.class);
        }
    }

    /**
     * Creates the synthetic bean.
     */
    public static final class CounterCreator implements SyntheticBeanCreator<Counter> {
        @Override
        public Counter create(SyntheticInjections injections, Parameters params) {
            return new Counter();
        }
    }

    /**
     * The synthetic bean of the request scope.
     */
    public static final class Counter {
        private int count;

        public int next() {
            return ++count;
        }
    }

    /**
     * Registers a context for the request scope that is always active.
     */
    public static final class RegisteringExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(RequestScoped.class, ActiveContext.class);
        }
    }

    /**
     * A context of the request scope that is always active, holding one instance of each bean.
     */
    public static final class ActiveContext implements AlterableContext {

        static final Map<Contextual<?>, Object> INSTANCES = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) INSTANCES.computeIfAbsent(contextual, key -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) INSTANCES.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            INSTANCES.remove(contextual);
        }
    }
}
