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
import jakarta.enterprise.context.AutoClose;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic bean takes the attributes of CDI 5.0 from a stereotype a stereotype it carries declares.
 */
class SyntheticStereotypeAttributesTest {

    private static final String SOURCE = """
        package composed;

        public class Anchor {
        }
        """;

    @Test
    void anAttributeOfAComposedStereotypeReachesTheBean() {
        try (ApplicationContext context = ExtensionDeployment.start(ComposingExtension.class, "composed.Anchor",
            SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.resolve(container.getBeans(Composed.class));
            assertTrue(bean.isAutoClose(), "the inner stereotype makes the bean auto-closeable");
        }
    }

    /**
     * A stereotype that makes its beans auto-closeable.
     */
    @Stereotype
    @AutoClose
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Closing {
    }

    /**
     * A stereotype that declares the one above.
     */
    @Stereotype
    @Closing
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Outer {
    }

    /**
     * Adds the bean with the outer stereotype.
     */
    public static final class ComposingExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Composed.class).type(Composed.class).scope(Dependent.class)
                .stereotype(Outer.class)
                .createWith(ComposedCreator.class);
        }
    }

    /**
     * Creates the bean.
     */
    public static final class ComposedCreator implements SyntheticBeanCreator<Composed> {
        @Override
        public Composed create(SyntheticInjections injections, Parameters params) {
            return new Composed();
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Composed {
    }
}
