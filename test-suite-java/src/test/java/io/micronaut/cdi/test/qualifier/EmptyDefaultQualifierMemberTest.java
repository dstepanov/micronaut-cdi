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
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier whose one member defaults to the empty string, used only with that member left out, is a qualifier
 * with a binding member all the same: Java leaves an empty string default out of the metadata it writes, and the
 * member is still compared, so a lookup by another value of it finds nothing.
 */
class EmptyDefaultQualifierMemberTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Sector {
        String value() default "";

        final class Literal extends AnnotationLiteral<Sector> implements Sector {

            private final String value;

            Literal(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return value;
            }
        }
    }

    interface Bin {
    }

    @Sector
    @Dependent
    static class PlainBin implements Bin {
    }

    @Test
    void aMemberLeftAtTheEmptyStringDefaultStillBinds() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Bin> bins = CDI.current().select(Bin.class, Any.Literal.INSTANCE);
            assertEquals(PlainBin.class, bins.select(new Sector.Literal("")).get().getClass());
            assertTrue(bins.select(new Sector.Literal("north")).isUnsatisfied(),
                "@Sector(\"north\") is not the @Sector the bean declares");
        }
    }
}
