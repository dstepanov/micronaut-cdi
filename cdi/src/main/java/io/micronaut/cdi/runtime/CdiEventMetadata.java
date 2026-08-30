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

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
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
final class CdiEventMetadata implements EventMetadata {

    private final Set<Annotation> qualifiers;
    private final @Nullable InjectionPoint injectionPoint;
    private final Type type;

    CdiEventMetadata(Set<Annotation> firedWith, @Nullable InjectionPoint injectionPoint, Type type) {
        Set<Annotation> all = new LinkedHashSet<>();
        all.add(Any.Literal.INSTANCE);
        all.addAll(firedWith);
        this.qualifiers = Set.copyOf(all);
        this.injectionPoint = injectionPoint;
        this.type = type;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return qualifiers;
    }

    @Override
    public @Nullable InjectionPoint getInjectionPoint() {
        return injectionPoint;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "EventMetadata[" + type.getTypeName() + "]";
    }
}
