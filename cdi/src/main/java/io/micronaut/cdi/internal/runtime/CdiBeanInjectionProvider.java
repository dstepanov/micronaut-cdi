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

import io.micronaut.context.BeanInjectionProvider;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.context.exceptions.NoSuchBeanException;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the exact CDI bean type while retaining the active injection point and dependent ownership.
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
        // Resolve qualifiers through CDI's final binding records, including build-time enhancements.
        // Then let the active Core context create the selected definition and retain its dependents.
        Bean<?> selected = container.resolve(container.beansOf(argument,
            CdiQualifier.declared(argument.getAnnotationMetadata())));
        if (selected == null) {
            if (nullable) {
                return null;
            }
            throw new NoSuchBeanException(argument, qualifier);
        }
        BeanDefinition<T> definition = (BeanDefinition<T>) ((CdiBean<?>) selected).definition();
        return resolutionContext.getBean(CdiInstance.askedAs(argument, definition), CdiInstance.only(definition));
    }
}
