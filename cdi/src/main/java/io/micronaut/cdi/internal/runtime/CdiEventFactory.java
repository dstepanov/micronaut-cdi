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

import io.micronaut.cdi.MicronautEvent;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.List;
import java.util.Set;


/**
 * Builds the {@code Event} an injection point asked for, of the type it named and qualified the way it was.
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiEventFactory<T> extends AbstractCdiInjectionPointBeanDefinition<Event<T>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Event<T>> getBeanType() {
        // the Micronaut event, so that an injection point may be declared with either type
        return (Class) MicronautEvent.class;
    }

    @Override
    public Set<Class<?>> getExposedTypes() {
        return Set.of(MicronautEvent.class, Event.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Event<T> build(BeanResolutionContext resolutionContext,
                             BeanContext context,
                             Argument<?> type,
                             List<CdiQualifier> qualifiers) {
        // an injection point that named no qualifier has the default one, and the event carries the
        // qualifiers of the point it was injected into (section 10.2.1)
        List<CdiQualifier> injected = qualifiers.isEmpty()
            ? List.of(CdiQualifier.DEFAULT) : qualifiers;
        InjectionPoint injectedAt = null;
        BeanResolutionContext.Segment<?, ?> segment = resolutionContext.getPath().currentSegment().orElse(null);
        if (segment != null) {
            injectedAt = CdiInjectionPoint.of(context.getBean(CdiBeanContainer.class), segment);
        }
        // an Argument is itself a Type, so the conversion is spelled out rather than left to overloading
        return new CdiEvent<>(context.getBean(ObserverRegistry.class),
            type, injected, injectedAt);
    }
}
