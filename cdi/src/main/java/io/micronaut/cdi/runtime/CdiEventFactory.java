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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.Event;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Builds the {@code Event} an injection point asked for, of the type it named and qualified the way it was.
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiEventFactory<T> extends CdiInjectionPointFactory<Event<T>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Event<T>> getBeanType() {
        return (Class) Event.class;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Event<T> build(BeanResolutionContext resolutionContext,
                             BeanContext context,
                             Argument<?> type,
                             Set<Annotation> qualifiers) {
        // an injection point that named no qualifier has the default one, and the event carries the
        // qualifiers of the point it was injected into (section 10.2.1)
        Set<Annotation> injected = qualifiers.isEmpty()
            ? Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE) : qualifiers;
        jakarta.enterprise.inject.spi.InjectionPoint injectedAt = null;
        BeanResolutionContext.Segment<?, ?> segment = resolutionContext.getPath().currentSegment().orElse(null);
        if (segment != null) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            injectedAt = CdiInjectionPoint.of(container.canonicalBean(segment.getDeclaringType()), segment);
        }
        // an Argument is itself a Type, so the conversion is spelled out rather than left to overloading
        return new CdiEvent<>(context.getBean(ObserverRegistry.class),
            CdiTypes.requiredTypeOf(type), injected, injectedAt);
    }
}
