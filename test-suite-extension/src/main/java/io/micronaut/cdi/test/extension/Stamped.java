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

import jakarta.enterprise.util.AnnotationLiteral;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation whose members are arrays of an enum and of primitives other than {@code int}, which an extension
 * adds to a class as an instance.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Stamped {

    /**
     * @return The grades
     */
    Grade[] grades();

    /**
     * @return The serial numbers
     */
    long[] serials();

    /**
     * @return The checks
     */
    boolean[] checks();

    /**
     * A grade.
     */
    enum Grade {
        FINE,
        COARSE
    }

    /**
     * The annotation as an instance, which is what an extension adds to a class.
     */
    final class Literal extends AnnotationLiteral<Stamped> implements Stamped {

        private final Grade[] grades;
        private final long[] serials;
        private final boolean[] checks;

        public Literal(Grade[] grades, long[] serials, boolean[] checks) {
            this.grades = grades;
            this.serials = serials;
            this.checks = checks;
        }

        @Override
        public Grade[] grades() {
            return grades;
        }

        @Override
        public long[] serials() {
            return serials;
        }

        @Override
        public boolean[] checks() {
            return checks;
        }
    }
}
