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

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnproxyableResolutionException;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of producers and of classes that are no beans, each deployed in memory: a class that is no bean is not held
 * to the rules of a normal scope it declares (section 3.1.1), a product of a normal scope whose type cannot be
 * proxied is an unproxyable resolution (3.11), and a disposer of an array disposes of what a producer narrowed to
 * that array type produces (3.4.3).
 */
class ProducerDefinitionRulesTest {

    @Test
    void aVetoedClassIsNotHeldToTheRulesOfItsNormalScope() {
        try (ApplicationContext context = InMemoryDeployment.start("valid.Consumer", """
            package valid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {

                @jakarta.enterprise.inject.Vetoed
                @jakarta.enterprise.context.ApplicationScoped
                public static class Vetoed {
                    public int calls;
                }
            }
            """)) {
            assertTrue(context.isRunning());
        }
    }

    @Test
    void aProductOfANormalScopeThatCannotBeProxiedIsAnUnproxyableResolution() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("valid.Consumer", """
            package valid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {

                public static class Fixed {
                    public final int value() {
                        return 1;
                    }
                }

                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                Fixed fixed() {
                    return new Fixed();
                }
            }
            """)) {
            Class<?> fixed = context.getClassLoader().loadClass("valid.Consumer$Fixed");
            Instance<Object> instance = context.getBean(BeanManager.class).createInstance();
            assertThrows(UnproxyableResolutionException.class, () -> instance.select(fixed).get());
        }
        DeploymentValidationTest.assertRejected("""
            package invalid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {

                @jakarta.inject.Inject
                Fixed fixed;

                public static class Fixed {
                    public final int value() {
                        return 1;
                    }
                }

                @jakarta.enterprise.context.Dependent
                public static class Factory {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.context.ApplicationScoped
                    Fixed fixed() {
                        return new Fixed();
                    }
                }
            }
            """, "cannot be proxied");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDisposerOfAnArrayDisposesOfAProductNarrowedToTheArrayType() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("valid.Consumer", """
            package valid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {

                public static final java.util.List<Object> DISPOSED = new java.util.concurrent.CopyOnWriteArrayList<>();

                public static class Volume {
                }

                @jakarta.enterprise.context.Dependent
                public static class Shelf {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed(Volume[].class)
                    Volume[] volumes() {
                        return new Volume[] {new Volume()};
                    }

                    void dispose(@jakarta.enterprise.inject.Disposes Volume[] volumes) {
                        DISPOSED.add(volumes);
                    }
                }
            }
            """)) {
            Class<?> consumer = context.getClassLoader().loadClass("valid.Consumer");
            Class<?> volumes = Class.forName("[Lvalid.Consumer$Volume;", false, context.getClassLoader());
            Instance<Object> lookup = context.getBean(BeanManager.class).createInstance();
            Instance.Handle<Object> handle = lookup.select((Class<Object>) volumes).getHandle();
            Object produced = handle.get();
            handle.destroy();
            List<?> disposed = (List<?>) consumer.getField("DISPOSED").get(null);
            assertEquals(List.of(produced), disposed);
        }
    }
}
