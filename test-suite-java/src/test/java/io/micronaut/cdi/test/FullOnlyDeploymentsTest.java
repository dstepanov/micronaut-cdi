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
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two deployments that differ from an implementation of CDI Full, as CONFORMANCE.md records: a class written as a
 * decorator is no bean, since decorators belong to CDI Full; and a dependency cycle through beans of pseudo-scopes
 * alone deploys, and fails as a bean of it is created, since section 5.4 does not require such a cycle to be
 * supported and nothing requires it to be detected as the container starts.
 */
class FullOnlyDeploymentsTest {

    @Test
    void aClassWrittenAsADecoratorIsNoBeanAndDecoratesNothing() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("decorating.Greeting", """
            package decorating;

            public interface Greeting {
                String greet();

                @jakarta.enterprise.context.Dependent
                class Plain implements Greeting {
                    public String greet() {
                        return "hello";
                    }
                }

                @jakarta.decorator.Decorator
                @jakarta.annotation.Priority(1)
                abstract class Loud implements Greeting {
                    @jakarta.inject.Inject
                    @jakarta.decorator.Delegate
                    Greeting delegate;

                    public String greet() {
                        return delegate.greet().toUpperCase();
                    }
                }
            }
            """)) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Class<?> greeting = context.getClassLoader().loadClass("decorating.Greeting");
            Class<?> loud = context.getClassLoader().loadClass("decorating.Greeting$Loud");
            assertTrue(lookup.select(loud).isUnsatisfied(), "the decorator is no bean");
            Object plain = lookup.select(greeting).get();
            assertTrue(plain.toString().contains("Plain"), "the one bean is the undecorated one: " + plain);
        }
    }

    @Test
    void aCycleThroughPseudoScopedBeansDeploysAndFailsAsABeanOfItIsCreated() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("cyclic.Egg", """
            package cyclic;

            @jakarta.enterprise.context.Dependent
            public class Egg {
                @jakarta.inject.Inject
                Hen hen;

                @jakarta.enterprise.context.Dependent
                public static class Hen {
                    @jakarta.inject.Inject
                    Egg egg;
                }
            }
            """)) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Class<?> egg = context.getClassLoader().loadClass("cyclic.Egg");
            assertThrows(RuntimeException.class, () -> lookup.select(egg).get());
        }
    }
}
