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
package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A parameter is handed back exactly as the extension gave it (the {@code Parameters} contract of CDI 4.1): a value of
 * a primitive type may be asked for by the primitive type or its wrapper, any other type is a
 * {@code ClassCastException} rather than a conversion, an enum constant is a constant of the enum it was recorded
 * with, and an array handed out is the caller's to change.
 */
class CdiParametersTypesTest {

    enum First { READY }

    enum Second { READY }

    @Test
    void aPrimitiveIsHandedOutByItsTypeOrItsWrapperAndNothingElse() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                parameter("count", "INT").member("ints", new int[] {5}).build()));
            assertEquals(5, parameters.get("count", int.class));
            assertEquals(5, parameters.get("count", Integer.class));
            assertThrows(ClassCastException.class, () -> parameters.get("count", Long.class));
            assertThrows(ClassCastException.class, () -> parameters.get("count", String.class));
        }
    }

    @Test
    void aStringIsNotConvertedToAnotherType() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                parameter("text", "STRING").member("strings", new String[] {"42"}).build()));
            assertEquals("42", parameters.get("text", String.class));
            assertEquals("42", parameters.get("text", CharSequence.class));
            assertThrows(ClassCastException.class, () -> parameters.get("text", Integer.class));
        }
    }

    @Test
    void anEnumConstantIsAConstantOfTheEnumItWasRecordedWith() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                parameter("state", "ENUM").member("strings", new String[] {"READY"})
                    .member("type", new AnnotationClassValue<>(First.class)).build(),
                parameter("states", "ENUM").member("array", true).member("strings", new String[] {"READY"})
                    .member("type", new AnnotationClassValue<>(First.class)).build()));
            assertEquals(First.READY, parameters.get("state", First.class));
            assertEquals(First.READY, parameters.get("state", Enum.class));
            assertThrows(ClassCastException.class, () -> parameters.get("state", Second.class));
            assertArrayEquals(new First[] {First.READY}, parameters.get("states", First[].class));
            assertThrows(ClassCastException.class, () -> parameters.get("states", Second[].class));
        }
    }

    @Test
    void anArrayHandedOutIsTheCallersToChange() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CdiParameters parameters = new CdiParameters(context, List.of(
                parameter("limits", "INT").member("array", true).member("ints", new int[] {1, 2}).build(),
                parameter("flags", "BOOLEAN").member("array", true).member("booleans", new boolean[] {true}).build(),
                parameter("sizes", "LONG").member("array", true).member("longs", new long[] {3L}).build(),
                parameter("ratios", "DOUBLE").member("array", true).member("doubles", new double[] {.5}).build(),
                parameter("names", "STRING").member("array", true).member("strings", new String[] {"a"}).build(),
                parameter("types", "CLASS").member("array", true)
                    .member("classes", new AnnotationClassValue<?>[] {new AnnotationClassValue<>(String.class)})
                    .build()));
            parameters.get("limits", int[].class)[0] = 99;
            parameters.get("flags", boolean[].class)[0] = false;
            parameters.get("sizes", long[].class)[0] = 99L;
            parameters.get("ratios", double[].class)[0] = 99d;
            parameters.get("names", String[].class)[0] = "changed";
            parameters.get("types", Class[].class)[0] = Integer.class;
            assertArrayEquals(new int[] {1, 2}, parameters.get("limits", int[].class));
            assertArrayEquals(new boolean[] {true}, parameters.get("flags", boolean[].class));
            assertArrayEquals(new long[] {3L}, parameters.get("sizes", long[].class));
            assertArrayEquals(new double[] {.5}, parameters.get("ratios", double[].class));
            assertArrayEquals(new String[] {"a"}, parameters.get("names", String[].class));
            assertArrayEquals(new Class<?>[] {String.class}, parameters.get("types", Class[].class));
        }
    }

    private static io.micronaut.core.annotation.AnnotationValueBuilder<CdiSyntheticParameter> parameter(
        String name, String kind) {
        return AnnotationValue.builder(CdiSyntheticParameter.class).member("name", name).member("kind", kind);
    }
}
