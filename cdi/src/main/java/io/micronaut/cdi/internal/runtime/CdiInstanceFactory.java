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
import jakarta.enterprise.inject.Instance;


/**
 * Builds the {@code Instance} an injection point asked for, of the type it named and qualified the way it was.
 *
 * @param <T> The type looked up
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInstanceFactory<T> extends CdiInjectionPointFactory<Instance<T>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Instance<T>> getBeanType() {
        // the Micronaut lookup, so that an injection point may be declared with either type
        return (Class) io.micronaut.cdi.MicronautInstance.class;
    }

    @Override
    public java.util.Set<Class<?>> getExposedTypes() {
        // the built-in lookup has Provider among its bean types: Instance extends it
        return java.util.Set.of(
            io.micronaut.cdi.MicronautInstance.class, Instance.class, jakarta.inject.Provider.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Instance<T> build(BeanResolutionContext resolutionContext,
                                BeanContext context,
                                Argument<?> type,
                                java.util.List<CdiQualifier> qualifiers) {
        jakarta.enterprise.inject.spi.InjectionPoint injectedAt = null;
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
