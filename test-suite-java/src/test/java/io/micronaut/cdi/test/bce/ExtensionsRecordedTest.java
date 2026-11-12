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

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the SE bootstrap reads as evidence that a build compatible extension ran: what the compilation recorded of
 * it, whether the compilation was told to run it by a service entry or, as here, by the compiler option.
 */
class ExtensionsRecordedTest {

    private static final String SOURCE = """
        package recorded;

        public class Anchor {
        }
        """;

    @Test
    void anExtensionRunThroughTheCompilerOptionIsRecorded() {
        try (ApplicationContext context = ExtensionDeployment.start(RecordedExtension.class, "recorded.Anchor",
            SOURCE)) {
            assertTrue(MicronautSeContainerInitializer.extensionsRecordedIn(context)
                .contains(RecordedExtension.class.getName()));
            assertFalse(MicronautSeContainerInitializer.extensionsRecordedIn(context)
                .contains(ExtensionsRecordedTest.class.getName()));
        }
    }

    /**
     * Adds a synthetic bean, which records the extension.
     */
    public static final class RecordedExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Recorded.class).type(Recorded.class).scope(Dependent.class)
                .createWith(RecordedCreator.class);
        }
    }

    /**
     * Creates the bean.
     */
    public static final class RecordedCreator implements SyntheticBeanCreator<Recorded> {
        @Override
        public Recorded create(SyntheticInjections injections, Parameters params) {
            return new Recorded();
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Recorded {
    }
}
