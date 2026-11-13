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
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subclass that declares no bean defining annotation is not discovered by the scope it inherits (section 2.5.1), and
 * the registration phase is not told about it.
 */
class UndiscoveredSubclassRegistrationTest {

    static final List<String> DESCRIBED = new ArrayList<>();

    private static final String SOURCE = """
        package undiscovered.registration;

        @jakarta.enterprise.context.ApplicationScoped
        public class Base {

            public static class Sub extends Base {
            }

            @jakarta.enterprise.context.ApplicationScoped
            public static class Declared extends Base {
            }
        }
        """;

    @Test
    void theRegistrationPhaseIsNotToldAboutAnUndiscoveredSubclass() {
        DESCRIBED.clear();
        try (JavaParser parser = ExtensionCompilation.of(Describing.class)) {
            parser.generate("undiscovered.registration.Base", SOURCE);
        }
        assertEquals(List.of("undiscovered.registration.Base", "undiscovered.registration.Base$Declared"),
            DESCRIBED.stream().sorted().toList());
    }

    /**
     * Records the beans the registration phase is told about.
     */
    public static final class Describing implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void bean(BeanInfo bean) {
            if (bean.isClassBean() && bean.declaringClass().name().startsWith("undiscovered.registration.")) {
                DESCRIBED.add(bean.declaringClass().name());
            }
        }
    }
}
