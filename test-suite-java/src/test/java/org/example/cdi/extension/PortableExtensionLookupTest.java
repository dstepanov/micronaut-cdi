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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code BeanManager.getExtension} hands out the instance of a portable extension the container runs (section
 * 11.3.25), and an extension that observes a container lifecycle event asynchronously is a definition error
 * (section 11.5).
 */
class PortableExtensionLookupTest {

    @Dependent
    public static class Plain {
    }

    public static class Running implements Extension {
        void seen(@Observes AfterBeanDiscovery event) {
        }
    }

    public static class NotRunning implements Extension {
    }

    public static class Asynchronous implements Extension {
        void seen(@ObservesAsync AfterBeanDiscovery event) {
        }
    }

    @Test
    void theExtensionTheContainerRunsIsLookedUpByItsClass() {
        Running running = new Running();
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Plain.class).addExtensions(running).initialize()) {
            assertSame(running, container.getBeanManager().getExtension(Running.class));
            assertThrows(IllegalArgumentException.class,
                () -> container.getBeanManager().getExtension(NotRunning.class));
        }
    }

    @Test
    void anAsynchronousObserverOfALifecycleEventIsADefinitionError() {
        assertThrows(DefinitionException.class, () -> SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Plain.class).addExtensions(new Asynchronous()).initialize().close());
    }
}
