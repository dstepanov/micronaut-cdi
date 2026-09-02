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
 * A qualifier whose member is an enum, so that a synthetic bean qualified by it is matched by the member's
 * value rather than by the qualifier's type alone.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Tier {

    /**
     * The tier.
     *
     * @return The level
     */
    Level value();

    /**
     * The levels.
     */
    enum Level {
        GOLD, SILVER
    }

    /**
     * A literal of the qualifier.
     */
    final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Tier> implements Tier {
        private final Level value;

        public Literal(Level value) {
            this.value = value;
        }

        @Override
        public Level value() {
            return value;
        }
    }
}
