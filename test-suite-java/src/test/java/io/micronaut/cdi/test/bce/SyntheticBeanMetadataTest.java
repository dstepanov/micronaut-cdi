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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A synthetic bean that registers an injection point of its own {@code Bean} metadata (CDI 5.0, sections 5.5.8 and
 * 12.5) is handed the metadata the bean container reports of it.
 */
class SyntheticBeanMetadataTest {

    private static final String SOURCE = """
        package metadata;

        public class Anchor {
        }
        """;

    @Test
    void aSyntheticBeanIsHandedItsOwnMetadata() {
        try (ApplicationContext context = ExtensionDeployment.start(DescribedExtension.class, "metadata.Anchor",
            SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Described described = container.createInstance().select(Described.class).get();
            Bean<?> expected = container.resolve(container.getBeans(Described.class));
            assertNotNull(described.bean);
            assertEquals(expected, described.bean);
            assertEquals(expected.getTypes(), described.bean.getTypes());
            assertEquals(expected.getQualifiers(), described.bean.getQualifiers());
        }
    }

    /**
     * Adds the synthetic bean, with an injection point of its own metadata.
     */
    public static final class DescribedExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components, Types types) {
            components.addBean(Described.class)
                .type(Described.class)
                .scope(Dependent.class)
                .withInjectionPoint(types.parameterized(Bean.class, Described.class))
                .createWith(DescribedCreator.class);
        }
    }

    /**
     * Creates the bean with the metadata it obtained.
     */
    public static final class DescribedCreator implements SyntheticBeanCreator<Described> {
        @Override
        public Described create(SyntheticInjections injections, Parameters params) {
            return new Described(injections.get(new TypeLiteral<Bean<Described>>() { }));
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Described {

        final Bean<Described> bean;

        Described(Bean<Described> bean) {
            this.bean = bean;
        }
    }
}
