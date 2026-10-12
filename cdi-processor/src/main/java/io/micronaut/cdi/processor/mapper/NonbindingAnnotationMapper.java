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
import io.micronaut.context.annotation.NonBinding;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads {@code jakarta.enterprise.util.Nonbinding} as the Micronaut annotation of the same meaning, so that Micronaut
 * records a member excluded from the comparison of a qualifier the way it records one of its own: listed under
 * {@code $nonBinding} on each annotation that writes the member.
 *
 * <p>Micronaut Jakarta Interceptors maps the annotation the same way for interceptor bindings; this module maps it
 * on its own as well, so that what a qualifier compares does not hang on another processor being on the path.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NonbindingAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.NONBINDING;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(AnnotationValue.builder(NonBinding.class).build());
    }
}
