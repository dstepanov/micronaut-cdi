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

import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Set;


/**
 * Builds the {@code Instance} an injection point asked for, of the type it named and qualified the way it was.
 *
 * @param <T> The type looked up
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInstanceFactory<T> extends AbstractCdiInjectionPointBeanDefinition<Instance<T>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Instance<T>> getBeanType() {
        // the Micronaut lookup, so that an injection point may be declared with either type
        return (Class) MicronautInstance.class;
    }

    @Override
    public Set<Class<?>> getExposedTypes() {
        // the built-in lookup has Provider among its bean types: Instance extends it
        return Set.of(
            MicronautInstance.class, Instance.class, Provider.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Instance<T> build(BeanResolutionContext resolutionContext,
                                BeanContext context,
                                Argument<?> type,
                                List<CdiQualifier> qualifiers) {
        InjectionPoint injectedAt = null;
        BeanResolutionContext.Segment<?, ?> segment = resolutionContext.getPath().currentSegment().orElse(null);
        if (segment != null) {
            injectedAt = CdiInjectionPoint.of(context.getBean(CdiBeanContainer.class), segment);
        }
        // what the lookup creates of dependent instances belongs to the bean it is injected into, and is destroyed
        // with it: the resolver of that bean creates it, under the segment of this injection point
        return new CdiInstance<>(context, injectedAt, segment,
            OwnedDependents.ownedBy(context, resolutionContext.getDependencyResolver()), (Argument<T>) type,
            qualifiers);
    }
}
