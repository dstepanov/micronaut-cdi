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
package io.micronaut.cdi.processor.mapper;

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.InjectScope;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads {@code jakarta.enterprise.inject.TransientReference} as the Micronaut annotation of the same meaning.
 *
 * <p>Both say the same thing about a parameter: the dependent instance injected into it is needed only for the
 * one invocation, and is destroyed as soon as the invocation is over rather than living as long as the bean it
 * was injected into. Micronaut destroys the beans of an {@code InjectScope} parameter when the injection
 * completes, which is that rule.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class TransientReferenceAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.TRANSIENT_REFERENCE;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(annotation, AnnotationValue.builder(InjectScope.class).build());
    }
}
