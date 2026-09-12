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
package org.jboss.weld.util.reflection;

import io.micronaut.cdi.runtime.CdiTypes;

import java.lang.reflect.Type;

/**
 * The one reflection helper of Weld the tests use: the raw class of a type.
 */
public final class Reflections {

    private Reflections() {
    }

    public static Class<?> getRawType(Type type) {
        Class<?> raw = CdiTypes.rawClassOf(type);
        if (raw == null) {
            throw new IllegalArgumentException("No raw type for " + type);
        }
        return raw;
    }
}
