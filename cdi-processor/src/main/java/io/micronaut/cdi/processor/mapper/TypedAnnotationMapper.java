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
import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Narrows the bean types of a bean to the ones {@code jakarta.enterprise.inject.Typed} names.
 *
 * <p>Micronaut limits the types a bean is resolvable by with the {@code typed} member of its own bean annotation,
 * which is the same restriction. A {@code @Typed} with no type at all leaves the bean resolvable by nothing but
 * {@code java.lang.Object}, which the specification says as well, and which is written here as the empty list of
 * types it is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class TypedAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.TYPED;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        AnnotationClassValue<?>[] types = annotation.annotationClassValues(AnnotationMetadata.VALUE_MEMBER);
        return List.of(annotation, AnnotationValue.builder(Bean.class)
            .member("typed", types)
            .build());
    }
}
