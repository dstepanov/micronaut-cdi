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
package io.micronaut.cdi.test.inheritance;

import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;

/**
 * Declares an initializer in terms of its type variable beside an ordinary overload more specific than its erasure,
 * which the type a subclass binds the variable to fits as well.
 *
 * @param <T> The type
 */
@Vetoed
public class SpecificOverloadInitializer<T> {

    /**
     * What the ordinary overload takes.
     */
    public interface Marked {
    }

    /**
     * What the subclass binds the variable to.
     */
    @jakarta.enterprise.context.Dependent
    public static class Value implements Marked {
    }

    T value;

    @Inject
    public void initialize(T value) {
        this.value = value;
    }

    /**
     * Not an initializer.
     *
     * @param other Anything
     */
    public void initialize(Marked other) {
        throw new UnsupportedOperationException("not an initializer");
    }
}
