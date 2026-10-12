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
package io.micronaut.cdi.test.extension;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation the discovery phase makes a qualifier of, marking its {@link #note()} non-binding although nothing
 * on the member says so.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Ripe {

    /**
     * Whether the fruit is ripe.
     *
     * @return The ripeness
     */
    String value();

    /**
     * A note the extension leaves out of the comparison.
     *
     * @return The note
     */
    String note() default "";

    /**
     * The literal, for building instances at runtime.
     */
    final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Ripe> implements Ripe {

        private final String value;
        private final String note;

        /**
         * @param value The ripeness
         * @param note  The note
         */
        public Literal(String value, String note) {
            this.value = value;
            this.note = note;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String note() {
            return note;
        }
    }
}
