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

import io.micronaut.cdi.lang.model.ast.CurrentElement;
import io.micronaut.cdi.lang.model.ast.ElementMethodInfo;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two overloads that differ only in an array dimension of a parameter are two members to the language model: the
 * key a member is looked up again by in a later round tells them apart, so that the array overload is not read
 * back as the scalar one.
 */
class ArrayOverloadKeyTest {

    private static final String SOURCE = """
        package overloaded;

        @jakarta.enterprise.context.Dependent
        public class Handler {

            public void handle(int value) {
            }

            public void handle(int[] values) {
            }

            public void handle(int[][] grid) {
            }
        }
        """;

    @Test
    void overloadsThatDifferInAnArrayDimensionHaveKeysOfTheirOwn() {
        Keys.KEYS.clear();
        try (ApplicationContext ignored = ExtensionDeployment.start(Keys.class, "overloaded.Handler", SOURCE)) {
            assertEquals(3, Keys.KEYS.size(), Keys.KEYS::toString);
        }
    }

    /**
     * Records the keys of the overloads.
     */
    public static final class Keys implements BuildCompatibleExtension {

        static final Set<String> KEYS = ConcurrentHashMap.newKeySet();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("overloaded.Handler")) {
                bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("handle"))
                    .forEach(method -> KEYS.add(CurrentElement.keyOf(((ElementMethodInfo) method).methodElement())));
            }
        }
    }
}
