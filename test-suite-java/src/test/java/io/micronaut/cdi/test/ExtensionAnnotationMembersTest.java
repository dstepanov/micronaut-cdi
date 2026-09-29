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

import io.micronaut.cdi.test.extension.Stampable;
import io.micronaut.cdi.test.extension.Stamped;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * An annotation an extension adds as an instance keeps every member it was written with, arrays of an enum and of
 * primitives other than {@code int} included.
 */
class ExtensionAnnotationMembersTest {

    @Stampable
    @Dependent
    static class Parcel {
    }

    @Test
    void theArrayMembersOfAnAddedAnnotationAreKept() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AnnotationMetadata metadata = context.getBeanDefinition(Parcel.class).getAnnotationMetadata();

            assertArrayEquals(new Stamped.Grade[]{Stamped.Grade.FINE, Stamped.Grade.COARSE},
                metadata.enumValues(Stamped.class, "grades", Stamped.Grade.class));
            assertArrayEquals(new long[]{7L, 11L},
                metadata.getValue(Stamped.class, "serials", long[].class).orElse(null));
            assertArrayEquals(new boolean[]{true, false},
                metadata.getValue(Stamped.class, "checks", boolean[].class).orElse(null));
        }
    }
}
