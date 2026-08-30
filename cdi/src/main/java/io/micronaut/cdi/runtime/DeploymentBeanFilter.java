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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;

import java.util.function.Predicate;

/**
 * Narrows what the container reports as its beans, for an environment that holds only part of what was compiled.
 *
 * <p>Micronaut's own narrowing — the {@code beansPredicate} of the context builder — decides what the context
 * will resolve. The container's programmatic lookup reads the compiled definitions directly, so an environment
 * that narrowed the context registers the same rule here and the two stay in agreement. The technology
 * compatibility kit's harness is such an environment: one deployment is one archive's classes.</p>
 *
 * <p>Where none is registered, everything compiled is reported, which is the ordinary case.</p>
 *
 * @param includes Whether the definition belongs to this deployment
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public record DeploymentBeanFilter(Predicate<BeanDefinition<?>> includes) {
}
