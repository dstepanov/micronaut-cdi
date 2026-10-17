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
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.Iterator;

/**
 * Builds the {@code InjectionPoint} of section 2.5.2.5: a dependent bean may be told where it was injected.
 *
 * <p>The answer is on the resolution path: the metadata is being injected into a bean, and the segment above
 * that bean's own is the injection point the bean is being created for. A bean an injected {@code Instance}
 * creates is created on the path of the point the lookup was injected into, under a segment that carries that
 * point as the lookup was narrowed ({@link LookupSegment}). A bean that is not being injected anywhere — obtained
 * through a lookup injected nowhere — has no such segment, and the metadata is null, which is what the
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
    public java.util.List<Argument<?>> getTypeArguments() {
        // unlike the event and the lookup, the injection point metadata is not parameterized at all
        return java.util.List.of();
    }

    @Override
    @org.jspecify.annotations.Nullable
    protected InjectionPoint build(BeanResolutionContext resolutionContext,
                                   BeanContext context,
                                   Argument<?> type,
                                   java.util.List<CdiQualifier> qualifiers) {
        Iterator<BeanResolutionContext.Segment<?, ?>> segments = resolutionContext.getPath().iterator();
        if (segments.hasNext()) {
            // the first segment is this metadata's own injection into the bean being created; the ones after
            // that with the same declaring bean are that bean's own construction, not places it is going
            BeanResolutionContext.Segment<?, ?> self = segments.next();
            if (!isOfTheMetadata(self)) {
                // the metadata was resolved by its definition within the creation of a bean, which pushes no
                // segment of its own (OwnedDependents.registrationAtCurrentPoint): the creation of a synthetic bean
                // resolves it so for the injection point the bean is created for, which heads the path
                return of(context, self);
            }
            while (segments.hasNext()) {
                BeanResolutionContext.Segment<?, ?> outer = segments.next();
                // a lookup's segment is where the bean is going even where the lookup belongs to a bean of the
                // same class: a dependent bean may look another instance of its own class up
                if (outer instanceof LookupSegment<?, ?> || !outer.getDeclaringType().equals(self.getDeclaringType())) {
                    return of(context, outer);
                }
            }
        }
        // a bean obtained through a lookup injected nowhere is created in a resolution of its own, whose path is
        // the bean's alone
        return null;
    }

    private static InjectionPoint of(BeanContext context, BeanResolutionContext.Segment<?, ?> segment) {
        return CdiInjectionPoint.of(context.getBean(CdiBeanContainer.class), segment);
    }

    /**
     * Whether a segment is an injection of the metadata itself - its own injection into the bean being created -
     * rather than a place that bean is going.
     */
    private static boolean isOfTheMetadata(BeanResolutionContext.Segment<?, ?> segment) {
        Argument<?> argument = segment.getArgument();
        return argument == null || InjectionPoint.class.isAssignableFrom(argument.getType());
    }
}
