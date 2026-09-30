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
package io.micronaut.cdi.runtime;

import io.micronaut.cdi.runtime.type.SpecificationTypes;
import io.micronaut.core.annotation.Internal;

import java.lang.reflect.Type;

/**
 * Makes a parameterized type of the reflection API, for the module that reads classes and for a program that
 * has the parts of one to hand.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiParameterizedType {

    private CdiParameterizedType() {
    }

    /**
     * The parameterized form of the given class over the given arguments.
     *
     * @param type      The raw class
     * @param arguments The type arguments
     * @return The parameterized type
     */
    public static Type of(Class<?> type, Type[] arguments) {
        return SpecificationTypes.parameterized(type, arguments);
    }
}
