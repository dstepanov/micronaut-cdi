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
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.Set;

/**
 * Builds the {@code InjectionPoint} of section 2.5.2.5: a dependent bean may be told where it was injected.
 *
 * <p>The answer is on the resolution path: the metadata is being injected into a bean, and the segment above
 * that bean's own is the injection point the bean is being created for. A bean that is not being injected
 * anywhere — obtained programmatically — has no such segment; if a lookup left the injection point it was
 * itself injected into, that is the answer, and otherwise the metadata is null, which is what the
 * specification says a non-contextual object sees.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiCurrentInjectionPointFactory extends CdiInjectionPointFactory<InjectionPoint> {

    public CdiCurrentInjectionPointFactory() {
        super(true);
    }

    @Override
    public Class<InjectionPoint> getBeanType() {
        return InjectionPoint.class;
    }

    @Override
    @org.jspecify.annotations.Nullable
    protected InjectionPoint build(BeanResolutionContext resolutionContext,
                                   BeanContext context,
                                   Argument<?> type,
                                   Set<Annotation> qualifiers) {
        Iterator<BeanResolutionContext.Segment<?, ?>> segments = resolutionContext.getPath().iterator();
        if (segments.hasNext()) {
            // the first segment is this metadata's own injection into the bean being created; the ones after
            // that with the same declaring bean are that bean's own construction, not places it is going
            BeanResolutionContext.Segment<?, ?> self = segments.next();
            while (segments.hasNext()) {
                BeanResolutionContext.Segment<?, ?> outer = segments.next();
                if (!outer.getDeclaringType().equals(self.getDeclaringType())) {
                    CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
                    return CdiInjectionPoint.of(container.canonicalBean(outer.getDeclaringType()), outer);
                }
            }
        }
        return CurrentInjectionPoint.current();
    }
}
