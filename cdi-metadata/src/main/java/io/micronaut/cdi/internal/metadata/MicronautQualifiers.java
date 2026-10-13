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
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;

import java.util.Set;

/**
 * The qualifiers Micronaut writes on a bean of its own accord, which are not qualifiers of the bean as far as the
 * specification is concerned: what the processor reads a bean's qualifiers from, and what the container compares
 * them by, both leave these out.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class MicronautQualifiers {

    /**
     * The names of the qualifiers: Micronaut's primary and secondary beans, its {@code Any}, which on a bean
     * satisfies every lookup, and the type a bean is narrowed to.
     */
    public static final Set<String> NAMES = Set.of(
        "io.micronaut.context.annotation.Primary",
        "io.micronaut.context.annotation.Secondary",
        "io.micronaut.context.annotation.Any",
        "io.micronaut.context.annotation.Type"
    );

    private MicronautQualifiers() {
    }

    /**
     * Whether the qualifier of the given name is one Micronaut writes of its own accord.
     *
     * @param name The name of the qualifier annotation
     * @return Whether it is
     */
    public static boolean isMicronautOwn(String name) {
        return NAMES.contains(name);
    }
}
