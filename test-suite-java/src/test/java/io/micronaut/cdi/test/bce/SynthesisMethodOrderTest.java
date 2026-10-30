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

import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The synthetic components of an extension are identified by the order its synthesis methods described them in,
 * and the methods of one priority run in the order of their names, which every compilation sees alike: the order
 * reflection hands the methods out in differs from one JVM to another, and with it the identity of each component.
 */
class SynthesisMethodOrderTest {

    private static final String SOURCE = """
        package ordered;

        @jakarta.enterprise.context.Dependent
        public class Subject {
        }
        """;

    @Test
    void theComponentsAreNumberedInTheOrderOfTheMethodNames() {
        try (ApplicationContext context = ExtensionDeployment.start(TwoMethods.class, "ordered.Subject", SOURCE)) {
            Map<String, String> creatorById = new TreeMap<>();
            context.getBeanDefinitions(Qualifiers.byStereotype(CdiSyntheticBean.class)).forEach(definition -> {
                String id = definition.getAnnotationMetadata().stringValue(CdiSyntheticBean.class, "id").orElseThrow();
                if (id.startsWith(TwoMethods.class.getName() + "#")) {
                    creatorById.put(id, definition.getBeanType().getSimpleName());
                }
            });
            assertEquals(Map.of(
                TwoMethods.class.getName() + "#1", "FirstCreator",
                TwoMethods.class.getName() + "#2", "LastCreator"), creatorById);
        }
    }

    public static final class TwoMethods implements BuildCompatibleExtension {

        @Synthesis
        public void zLast(SyntheticComponents components) {
            components.addBean(Last.class).type(Last.class).createWith(LastCreator.class);
        }

        @Synthesis
        public void aFirst(SyntheticComponents components) {
            components.addBean(First.class).type(First.class).createWith(FirstCreator.class);
        }
    }

    public static final class First {
    }

    public static final class Last {
    }

    public static final class FirstCreator implements SyntheticBeanCreator<First> {
        @Override
        public First create(Instance<Object> lookup, Parameters params) {
            return new First();
        }
    }

    public static final class LastCreator implements SyntheticBeanCreator<Last> {
        @Override
        public Last create(Instance<Object> lookup, Parameters params) {
            return new Last();
        }
    }
}
