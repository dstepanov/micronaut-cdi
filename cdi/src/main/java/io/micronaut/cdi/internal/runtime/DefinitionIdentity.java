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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import org.jspecify.annotations.Nullable;

/**
 * A definition as a key compared by identity rather than by its own equality: two runtime definitions of one
 * class - the synthetic beans an extension described - are equal to each other, and are not the same bean.
 *
 * @param definition The definition
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
record DefinitionIdentity(BeanDefinition<?> definition) {

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DefinitionIdentity identity && identity.definition == definition;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(definition);
    }
}
