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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injection points an extension registers for a synthetic bean are among the bean's injection points, in the
 * registration phase and at runtime, and belong to it however they are looked up.
 */
class SyntheticInjectionPointMetadataTest {

    static final List<String> DESCRIBED = new ArrayList<>();
    static final List<Bean<?>> DISPOSAL_OWNERS = new ArrayList<>();

    private static final String SOURCE = """
        package described;

        public class Anchor {
        }
        """;

    @Test
    void theRegisteredPointsAreTheBeansInjectionPoints() {
        DESCRIBED.clear();
        DISPOSAL_OWNERS.clear();
        try (ApplicationContext context = ExtensionDeployment.start(DescribingExtension.class, "described.Anchor",
            SOURCE)) {
            assertEquals(List.of(Resource.class.getName() + " " + Marked.class.getName()), DESCRIBED, "the registration phase is told the registered point");

            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.resolve(container.getBeans(Described.class));
            Set<InjectionPoint> points = bean.getInjectionPoints();
            assertEquals(1, points.size(), points.toString());
            InjectionPoint point = points.iterator().next();
            assertEquals(Resource.class, point.getType());
            assertTrue(point.getQualifiers().contains(Marked.Literal.INSTANCE), point.getQualifiers().toString());
            assertSame(bean, point.getBean());

            Instance<Object> lookup = container.createInstance();
            Instance.Handle<Described> handle = lookup.select(Described.class).getHandle();
            handle.get();
            handle.destroy();
            assertEquals(1, DISPOSAL_OWNERS.size());
            assertEquals(bean, DISPOSAL_OWNERS.get(0), "what a disposal obtains belongs to the synthetic bean too");
        }
    }

    /**
     * The qualifier of the registered point.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {

        /**
         * The literal.
         */
        final class Literal extends AnnotationLiteral<Marked> implements Marked {
            static final Literal INSTANCE = new Literal();
        }
    }

    /**
     * What the creation and the disposal obtain, told the point it is obtained for.
     */
    @Dependent
    @Marked
    public static class Resource {
        @Inject
        InjectionPoint injectionPoint;
    }

    /**
     * Adds the synthetic bean and reads what the registration phase is told of it.
     */
    public static final class DescribingExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Described.class)
                .type(Described.class)
                .scope(Dependent.class)
                .withInjectionPoint(Resource.class, Marked.Literal.INSTANCE)
                .createWith(DescribedCreator.class)
                .disposeWith(DescribedDisposer.class);
        }

        @Registration(types = Described.class)
        public void describe(BeanInfo bean) {
            for (InjectionPointInfo point : bean.injectionPoints()) {
                DESCRIBED.add(point.type().asClass().declaration().name() + " "
                    + point.qualifiers().iterator().next().name());
            }
        }
    }

    /**
     * Creates the bean.
     */
    public static final class DescribedCreator implements SyntheticBeanCreator<Described> {
        @Override
        public Described create(SyntheticInjections injections, Parameters params) {
            injections.get(Resource.class, Marked.Literal.INSTANCE);
            return new Described();
        }
    }

    /**
     * Disposes of the bean, reading whose point what it obtains belongs to.
     */
    public static final class DescribedDisposer implements SyntheticBeanDisposer<Described> {
        @Override
        public void dispose(Described instance, SyntheticInjections injections, Parameters params) {
            DISPOSAL_OWNERS.add(injections.get(Resource.class, new Annotation[] {Marked.Literal.INSTANCE})
                .injectionPoint.getBean());
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Described {
    }
}
