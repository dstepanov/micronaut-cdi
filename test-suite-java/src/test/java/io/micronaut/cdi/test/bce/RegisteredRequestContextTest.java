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
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context an extension registers for the request scope stands beside the container's own (CDI 5.0, section 6.4):
 * while the container's request context is active and the registered one is not, the beans of the scope are held by
 * the container's, one instance for the request.
 */
class RegisteredRequestContextTest {

    private static final String SOURCE = """
        package requested;

        @jakarta.enterprise.context.RequestScoped
        public class Visit {

            private int count;

            public int visit() {
                return ++count;
            }
        }
        """;

    @Test
    void theContainersActiveRequestContextServesTheScopeBesideAnInactiveRegisteredOne() throws Exception {
        try (ApplicationContext context = ExtensionDeployment.start(RegisteringExtension.class, "requested.Visit",
            SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Class<?> visitClass = context.getClassLoader().loadClass("requested.Visit");
            Method visit = visitClass.getMethod("visit");
            Instance<?> visits = container.createInstance().select(visitClass);
            assertThrows(ContextNotActiveException.class, () -> container.getContext(RequestScoped.class),
                "neither context is active");
            RequestContextController controller = container.createInstance()
                .select(RequestContextController.class).get();
            controller.activate();
            try {
                assertTrue(container.getContext(RequestScoped.class).isActive());
                assertEquals(1, visit.invoke(visits.get()));
                assertEquals(2, visit.invoke(visits.get()), "the request holds one instance");
            } finally {
                controller.deactivate();
            }
            controller.activate();
            try {
                assertEquals(1, visit.invoke(visits.get()), "a new request holds a new instance");
            } finally {
                controller.deactivate();
            }
        }
    }

    /**
     * Registers a context for the request scope that is never active.
     */
    public static final class RegisteringExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(RequestScoped.class, InactiveContext.class);
        }
    }

    /**
     * A context of the request scope that is never active.
     */
    public static final class InactiveContext implements AlterableContext {

        @Override
        public Class<? extends Annotation> getScope() {
            return RequestScoped.class;
        }

        @Override
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            throw new ContextNotActiveException();
        }

        @Override
        public <T> T get(Contextual<T> contextual) {
            throw new ContextNotActiveException();
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            throw new ContextNotActiveException();
        }
    }
}
