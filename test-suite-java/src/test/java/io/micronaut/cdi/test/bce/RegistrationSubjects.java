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

/**
 * The types a registration method of {@link RegistrationDescriptionTest} asks for, which the classes it compiles
 * implement and observe.
 */
public final class RegistrationSubjects {

    private RegistrationSubjects() {
    }

    /**
     * Implemented by the beans the registration asks for, and by one that leaves it out with {@code Typed}.
     */
    public interface Shape {
    }

    /**
     * Observed by an observer a generic superclass declares.
     *
     * @param from Who sent it
     */
    public record Ping(String from) {
    }
}
