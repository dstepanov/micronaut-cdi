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
package io.micronaut.cdi.test;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type the container reports - the type of an injection point, a bean type - is equal to the type the JDK reflects
 * for the same declaration, with the same hash code and name, a wildcard and a nested class's owner included: what a
 * program compares it with is usually a {@code TypeLiteral}'s type or a member's generic type.
 */
class ReportedTypeEqualityTest {

    @Dependent
    public static class Gauge<T> {
        @Inject
        InjectionPoint at;
    }

    @Dependent
    public static class Panel {
        @Inject
        Gauge<? extends Number> numbers;
        @Inject
        Gauge<? super Integer> integers;
        @Inject
        Gauge<Map.Entry<String, List<?>>> entries;
    }

    @Test
    void theTypeOfAnInjectionPointIsTheTypeTheJdkReflects() throws NoSuchFieldException {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            Panel panel = container.select(Panel.class).get();
            assertReflected(Panel.class.getDeclaredField("numbers").getGenericType(), panel.numbers.at.getType());
            assertReflected(Panel.class.getDeclaredField("integers").getGenericType(), panel.integers.at.getType());
            assertReflected(Panel.class.getDeclaredField("entries").getGenericType(), panel.entries.at.getType());
            assertReflected(new TypeLiteral<Gauge<? extends Number>>() { }.getType(), panel.numbers.at.getType());
        }
    }

    private static void assertReflected(Type reflected, Type reported) {
        assertEquals(reflected, reported);
        assertEquals(reported, reflected);
        assertEquals(reflected.hashCode(), reported.hashCode(), "the hash code of " + reflected);
        assertEquals(reflected.getTypeName(), reported.getTypeName());
        Set<Type> types = new HashSet<>(Set.of(reported));
        assertTrue(types.contains(reflected), "a set of the reported type holds the reflected one");
    }
}
