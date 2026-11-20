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
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Types.ofClass(String)} returns {@code null} for a class that is not there, as its contract says, so that
 * an extension can probe for an optional dependency.
 */
class OptionalClassProbeTest {

    private static final String SOURCE = """
        package probing;

        @jakarta.enterprise.context.Dependent
        public class Probed {
        }
        """;

    @Test
    void aClassThatIsNotThereIsNull() {
        Probing.FOUND.clear();
        try (ApplicationContext ignored = ExtensionDeployment.start(Probing.class, "probing.Probed", SOURCE)) {
            assertEquals(List.of("absent null", "present probing.Probed"), Probing.FOUND);
        }
    }

    /**
     * Probes for a class that is there and one that is not.
     */
    public static final class Probing implements BuildCompatibleExtension {

        static final List<String> FOUND = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean, Types types) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("probing.Probed")) {
                FOUND.add("absent " + types.ofClass("optional.Client"));
                FOUND.add("present " + types.ofClass("probing.Probed").declaration().name());
            }
        }
    }
}
