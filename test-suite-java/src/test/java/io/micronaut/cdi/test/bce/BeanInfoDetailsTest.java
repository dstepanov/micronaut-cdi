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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the registration phase reports of a bean (CDI 4.1 section 12.4): the stereotypes it declares, its injection
 * points with their qualifiers, and the disposer method of a producer.
 */
class BeanInfoDetailsTest {

    private static final String SOURCE = """
        package detailed;

        public class Lamp {

            @jakarta.enterprise.inject.Stereotype
            @jakarta.enterprise.context.Dependent
            @jakarta.inject.Named
            @jakarta.annotation.Priority(7)
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
            public @interface Labelled {
            }

            @jakarta.inject.Qualifier
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface Blue {
            }

            public static class Wick {
            }

            @Labelled
            public static class Shade {

                @jakarta.inject.Inject
                @Blue
                Wick wick;

                @jakarta.inject.Inject
                Shade(Wick plain) {
                }

                Shade() {
                }
            }

            @jakarta.enterprise.context.Dependent
            public static class Maker {

                @jakarta.enterprise.inject.Produces
                @Blue
                Wick make(Shade shade) {
                    return new Wick();
                }

                void dispose(@jakarta.enterprise.inject.Disposes @Blue Wick wick, Shade shade) {
                }

                @jakarta.enterprise.inject.Produces
                Wick plain() {
                    return new Wick();
                }
            }
        }
        """;

    @Test
    void theRegistrationPhaseIsToldTheStereotypesInjectionPointsAndDisposerOfABean() {
        Recording.READ.clear();
        try (ApplicationContext context = ExtensionDeployment.start(Recording.class, "detailed.Lamp", SOURCE)) {
            assertEquals(List.of(
                "class Shade stereotypes [jakarta.enterprise.context.Dependent named alternative=false priority=7] "
                    + "points [detailed.Lamp.Wick [detailed.Lamp.Blue], detailed.Lamp.Wick "
                    + "[jakarta.enterprise.inject.Default]]",
                // the producer's own parameters: the disposer's are its disposer's, as the bean's Bean has them at
                // runtime and as ArC describes a producer
                "producer make stereotypes [] points [detailed.Lamp.Shade [jakarta.enterprise.inject.Default]] "
                    + "disposer dispose 0"),
                Recording.READ.stream().sorted().toList());
        }
    }

    /**
     * Records what the registration phase reports of the beans of the source.
     */
    public static final class Recording implements BuildCompatibleExtension {

        static final List<String> READ = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (!bean.declaringClass().name().startsWith("detailed.")) {
                return;
            }
            if (bean.isClassBean() && bean.declaringClass().simpleName().equals("Shade")) {
                READ.add("class Shade stereotypes " + stereotypes(bean) + " points " + points(bean));
            }
            if (bean.isProducerMethod() && bean.producerMethod().name().equals("make")) {
                DisposerInfo disposer = bean.disposer();
                READ.add("producer make stereotypes " + stereotypes(bean) + " points " + points(bean)
                    + " disposer " + disposer.disposerMethod().name() + " "
                    + disposer.disposerMethod().parameters().indexOf(disposer.disposedParameter()));
            }
        }

        private static String stereotypes(BeanInfo bean) {
            return bean.stereotypes().stream().map(BeanInfoDetailsTest.Recording::stereotype).toList().toString();
        }

        private static String stereotype(StereotypeInfo stereotype) {
            return (stereotype.defaultScope() == null ? "none" : stereotype.defaultScope().name())
                + (stereotype.isNamed() ? " named" : "") + " alternative=" + stereotype.isAlternative()
                + " priority=" + stereotype.priority();
        }

        private static String points(BeanInfo bean) {
            return bean.injectionPoints().stream().map(BeanInfoDetailsTest.Recording::point)
                .sorted().toList().toString();
        }

        private static String point(InjectionPointInfo point) {
            return point.type().asClass().declaration().name().replace('$', '.') + " "
                + point.qualifiers().stream().map(AnnotationInfo::name).map(name -> name.replace('$', '.'))
                .collect(Collectors.toList());
        }
    }
}
