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
package io.micronaut.cdi.test.qualifier;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Two qualifier interfaces whose members are annotated with each other: reading the non-binding members of one
 * reads the annotations of its members, which names the other, whose members name the first. The compilation
 * has to end, and the bean has to resolve.
 */
class MutuallyAnnotatedQualifierMembersTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Clockwise {
        @Nonbinding @Anticlockwise String turns() default "";
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Anticlockwise {
        @Nonbinding @Clockwise(turns = "one") String turns() default "";
    }

    @Dependent
    @Clockwise(turns = "two")
    public static class Dial {
    }

    @Test
    void aQualifierWhoseMembersNameEachOtherCompilesAndResolves() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertNotNull(CDI.current().select(Dial.class, new Clockwise() {
                @Override
                public String turns() {
                    return "other";
                }

                @Override
                public Class<Clockwise> annotationType() {
                    return Clockwise.class;
                }
            }).get());
        }
    }
}
