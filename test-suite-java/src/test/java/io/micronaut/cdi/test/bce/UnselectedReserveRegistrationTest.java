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

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reserve that no priority selects is no bean of the deployment, and the registration phase is not told about it,
 * its producers or its observers.
 */
class UnselectedReserveRegistrationTest {

    static final List<String> DESCRIBED = new ArrayList<>();

    private static final String SOURCE = """
        package reserved;

        @jakarta.enterprise.inject.Reserve
        @jakarta.enterprise.context.Dependent
        public class Unselected {

            @jakarta.enterprise.inject.Produces
            String produced() {
                return "unselected";
            }

            void observe(@jakarta.enterprise.event.Observes Integer event) {
            }

            @jakarta.enterprise.context.Dependent
            public static class Plain {

                @jakarta.enterprise.inject.Reserve
                @jakarta.enterprise.inject.Produces
                Long unselectedProducer() {
                    return 1L;
                }

                @jakarta.enterprise.inject.Reserve
                @jakarta.annotation.Priority(1)
                @jakarta.enterprise.inject.Produces
                Short selectedProducer() {
                    return 1;
                }
            }
        }
        """;

    @Test
    void theRegistrationPhaseIsToldOnlyAboutBeansOfTheDeployment() {
        DESCRIBED.clear();
        try (JavaParser parser = ExtensionCompilation.of(Describing.class)) {
            parser.generate("reserved.Unselected", SOURCE);
        }
        assertEquals(List.of("bean reserved.Unselected$Plain", "bean java.lang.Short"), DESCRIBED);
    }

    /**
     * Records what the registration phase is told about.
     */
    public static final class Describing implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void bean(BeanInfo bean) {
            String name = bean.isClassBean() ? bean.declaringClass().name()
                : bean.producerMethod().returnType().asClass().declaration().name();
            if (name.startsWith("reserved.") || name.startsWith("java.lang.")) {
                DESCRIBED.add("bean " + name);
            }
        }

        @Registration(types = Integer.class)
        public void observer(ObserverInfo observer) {
            DESCRIBED.add("observer " + observer.eventType());
        }
    }
}
