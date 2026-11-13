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
import jakarta.enterprise.inject.Any;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subclass that declares no bean defining annotation is not discovered by the scope it inherits (section 2.5.1),
 * whatever observer methods it inherits: they are its superclass's, and observe once, through the superclass's bean.
 */
class UndiscoveredSubclassObserverTest {

    private static final String SOURCE = """
        package undiscovered.observer;

        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;

        @ApplicationScoped
        public class Base {

            void observe(@Observes StringBuilder event) {
                event.append("observed;");
            }

            public static class Sub extends Base {
            }
        }
        """;

    @Test
    void anInheritedObserverMakesNoBeanOfTheSubclass() {
        try (ApplicationContext context = ExtensionDeployment.start(
            ExtensionCompilation.of(ProducerAttributesTest.Nothing.class), "undiscovered.observer.Base", SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(0, container.getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .filter(bean -> bean.getBeanClass().getName().equals("undiscovered.observer.Base$Sub")).count());
            StringBuilder event = new StringBuilder();
            container.getEvent().select(StringBuilder.class).fire(event);
            assertEquals("observed;", event.toString());
        }
    }
}
