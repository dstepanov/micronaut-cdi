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
package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The instance a context reports for a bean of the container is the one Micronaut's scope holds: once Micronaut
 * destroyed it, the context does not hand it out again (section 6.2, a destroyed instance is not returned).
 */
class ContextOfMicronautHeldBeanTest {

    @Test
    void anInstanceMicronautDestroyedIsNotReturnedAgain() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            @SuppressWarnings("unchecked")
            Bean<Gauge> bean = (Bean<Gauge>) container.resolve(container.getBeans(Gauge.class));
            Context applicationContext = container.getContext(ApplicationScoped.class);
            Gauge first = applicationContext.get(bean, container.createCreationalContext(bean));
            // destroyed through Micronaut, by its client proxy
            context.destroyBean(container.createInstance().select(Gauge.class).get());
            Gauge again = applicationContext.get(bean);
            if (again != null) {
                assertNotSame(first, again, "the context handed out the instance Micronaut destroyed");
            }
            assertNotSame(first, applicationContext.get(bean, container.createCreationalContext(bean)),
                "the context created nothing new and handed out the instance Micronaut destroyed");
        }
    }

    /**
     * An application scoped bean.
     */
    @ApplicationScoped
    public static class Gauge {

        int read() {
            return 0;
        }
    }
}
