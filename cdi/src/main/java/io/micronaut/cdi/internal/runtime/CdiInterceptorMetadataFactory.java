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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.Interceptor;

import java.util.List;

/**
 * Builds the {@code Interceptor} metadata of section 5.5.8 of CDI Lite: the {@code @Default Interceptor} injected
 * into an interceptor instance is that interceptor's own metadata, the {@link CdiInterceptor} the bean container's
 * {@code resolveInterceptors} answers with, read off the resolution path as {@link CdiBeanMetadataFactory} reads
 * a bean's.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInterceptorMetadataFactory extends AbstractCdiInjectionPointBeanDefinition<Interceptor<?>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Interceptor<?>> getBeanType() {
        return (Class) Interceptor.class;
    }

    @Override
    protected Interceptor<?> build(BeanResolutionContext resolutionContext,
                                   BeanContext context,
                                   Argument<?> type,
                                   List<CdiQualifier> qualifiers) {
        Bean<?> declaring = CdiBeanMetadataFactory.declaringBean(context.getBean(CdiBeanContainer.class),
            resolutionContext);
        if (declaring instanceof Interceptor<?> interceptor) {
            return interceptor;
        }
        // the processor refuses the point on anything but an interceptor class (section 5.5.8)
        throw new IllegalStateException("The Interceptor metadata was asked for by " + declaring
            + ", which is not an interceptor");
    }
}
