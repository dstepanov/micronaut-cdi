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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer.Selection;
import io.micronaut.context.BeanInjectionProvider;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.context.exceptions.NoSuchBeanException;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Resolves an injection point of a bean of the specification the way the container resolves every one of them
 * ({@link CdiBeanContainer#selectionFor}), while retaining the active injection point and dependent
 * ownership: the active resolution context creates the bean selected. A point no bean of the specification
 * answers is resolved by Micronaut's rules, as the point of a bean of Micronaut's own would be.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class CdiBeanInjectionProvider implements BeanInjectionProvider {
    private final CdiBeanContainer container;

    public CdiBeanInjectionProvider(CdiBeanContainer container) {
        this.container = container;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> @Nullable T get(BeanResolutionContext resolutionContext, Argument<T> argument,
                               @Nullable Qualifier<T> qualifier, boolean nullable) {
        return switch (container.selectionFor(argument)) {
            case Selection.Selected selected -> {
                BeanDefinition<T> definition = (BeanDefinition<T>) selected.definition();
                // the definition CDI chose is resolved as it is, with no candidate lookup of its own: a qualifier
                // narrowing the candidates to it would become part of the key its scope holds the instance under
                yield (T) CdiResolution.orPrimitiveDefault(resolutionContext.getBean(definition,
                    CdiInstance.askedAs(resolutionContext.getContext(), argument, definition)), argument);
            }
            case Selection.Unselected.UNSATISFIED -> {
                if (nullable) {
                    yield null;
                }
                throw new NoSuchBeanException(argument, qualifier);
            }
            case Selection.Unselected.MICRONAUT -> {
                // no bean of the specification is a candidate: Micronaut's rules among the beans of Micronaut's own,
                // with the qualifier Micronaut read off the point
                Qualifier<T> micronaut = NonCdiBeanQualifier.forPoint(argument, qualifier);
                yield nullable ? resolutionContext.findBean(argument, micronaut).orElse(null)
                    : resolutionContext.getBean(argument, micronaut);
            }
        };
    }
}
