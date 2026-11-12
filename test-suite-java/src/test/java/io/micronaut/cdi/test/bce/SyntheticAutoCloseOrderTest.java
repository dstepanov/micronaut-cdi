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

import io.micronaut.context.ApplicationContext;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An auto-closeable synthetic bean is closed before the dependent instances its creation obtained are destroyed
 * (CDI 5.0, sections 2.10 and 5.5.3): its {@code close()} may still use them.
 */
class SyntheticAutoCloseOrderTest {

    static final List<String> EVENTS = new ArrayList<>();

    private static final String SOURCE = """
        package closing;

        public class Anchor {
        }
        """;

    @Test
    void aSyntheticBeanIsClosedBeforeWhatItsCreationObtainedIsDestroyed() {
        EVENTS.clear();
        try (ApplicationContext context = ExtensionDeployment.start(ClosingExtension.class, "closing.Anchor",
            SOURCE)) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Closing> handle = lookup.select(Closing.class).getHandle();
            handle.get();
            handle.destroy();
        }
        assertEquals(List.of("closed using resource", "resource destroyed"), EVENTS);
    }

    /**
     * Adds the auto-closeable synthetic bean, with an injection point of the resource.
     */
    public static final class ClosingExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Closing.class)
                .type(Closing.class)
                .scope(Dependent.class)
                .autoClose(true)
                .withInjectionPoint(Resource.class)
                .createWith(ClosingCreator.class);
        }
    }

    /**
     * Creates the bean with the resource it closes with.
     */
    public static final class ClosingCreator implements SyntheticBeanCreator<Closing> {
        @Override
        public Closing create(SyntheticInjections injections, Parameters params) {
            return new Closing(injections.get(Resource.class));
        }
    }

    /**
     * The dependent resource the creation obtains, and the synthetic bean uses as it is closed.
     */
    @Dependent
    public static class Resource implements Supplier<String> {

        @jakarta.annotation.PreDestroy
        void destroyed() {
            EVENTS.add("resource destroyed");
        }

        @Override
        public String get() {
            return "resource";
        }
    }

    /**
     * The synthetic bean, which uses the resource as it is closed.
     */
    public static final class Closing implements AutoCloseable {

        private final Supplier<String> resource;

        Closing(Supplier<String> resource) {
            this.resource = resource;
        }

        @Override
        public void close() {
            EVENTS.add("closed using " + resource.get() + (EVENTS.contains("resource destroyed") ? " (destroyed)" : ""));
        }
    }
}
