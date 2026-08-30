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

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A qualifier with a member that takes part in resolution, for the tests that read member values back.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Flavor {

    /**
     * The flavour.
     *
     * @return The flavour
     */
    String value();

    /**
     * The literal, for building instances at runtime.
     */
    final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Flavor> implements Flavor {

        private final String value;

        public Literal(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
