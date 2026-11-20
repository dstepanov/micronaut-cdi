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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A static producer method of a product of a normal scope, declared by a bean of a normal scope, compiles and
 * produces (CDI 4.1 section 3.2): the scope of what it produces is no advice of the method, which is static.
 */
class StaticScopedProducerTest {

    @Test
    @Disabled("Micronaut's processor takes the scoped proxy the method declares for its product for advice of the "
        + "method, which is static (DeclaredBeanElementCreator.visitAopMethod, see CONFORMANCE.md, Open points in "
        + "Micronaut Core)")
    void aStaticProducerOfANormalScopedProductOnANormalScopedBeanProduces() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("staticproducer.Warehouse", """
            package staticproducer;

            @jakarta.enterprise.context.ApplicationScoped
            public class Warehouse {

                public static class Crate {
                    public String label() {
                        return "crate";
                    }
                }

                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                static Crate crate() {
                    return new Crate();
                }
            }
            """)) {
            Class<?> crate = context.getClassLoader().loadClass("staticproducer.Warehouse$Crate");
            Object produced = context.getBean(CdiBeanContainer.class).createInstance().select(crate).get();
            assertEquals("crate", crate.getMethod("label").invoke(produced));
        }
    }
}
