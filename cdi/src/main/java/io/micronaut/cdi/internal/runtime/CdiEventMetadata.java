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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an observer method may be told about the event that notified it: the type it was fired as, the
 * qualifiers it was fired with — {@code Any} always among them — and, when it was fired through an injected
 * {@code Event}, the injection point the event was injected at.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiEventMetadata implements EventMetadata {

    // the container the event was fired in, which makes the qualifiers it was fired with annotation instances
    private final BeanContext beanContext;
    private final List<CdiQualifier> firedWith;
    private final @Nullable InjectionPoint injectionPoint;
    private final Argument<?> eventType;
    private volatile @Nullable Type type;
    private volatile @Nullable Set<Annotation> qualifiers;

    /**
     * The metadata of a firing.
     *
     * @param beanContext    The context of the container the event was fired in
     * @param firedWith      The qualifiers the event was fired with, made annotation instances only if asked for
     * @param injectionPoint The injection point of the {@code Event} it was fired through, if any
     * @param eventType      The type the event was fired as
     */
    public CdiEventMetadata(BeanContext beanContext, List<CdiQualifier> firedWith,
                            @Nullable InjectionPoint injectionPoint, Argument<?> eventType) {
        this.beanContext = beanContext;
        this.firedWith = firedWith;
        this.injectionPoint = injectionPoint;
        this.eventType = eventType;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        // annotation instances, which only an observer that asks for them needs
        Set<Annotation> resolved = qualifiers;
        if (resolved == null) {
            Set<Annotation> all = new LinkedHashSet<>();
            all.add(Any.Literal.INSTANCE);
            all.addAll(CdiQualifier.instances(beanContext, firedWith));
            resolved = Set.copyOf(all);
            qualifiers = resolved;
        }
        return resolved;
    }

    @Override
    public @Nullable InjectionPoint getInjectionPoint() {
        return injectionPoint;
    }

    @Override
    public Type getType() {
        Type resolved = type;
        if (resolved == null) {
            resolved = eventType.asType();
            type = resolved;
        }
        return resolved;
    }

    @Override
    public String toString() {
        return "EventMetadata[" + getType().getTypeName() + "]";
    }
}
