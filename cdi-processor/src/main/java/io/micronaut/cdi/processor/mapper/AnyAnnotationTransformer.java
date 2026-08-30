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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationTransformer;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads {@code Any} into the marker that says it was written, and nothing else: every bean has the {@code Any}
 * qualifier, so where it was written it narrows nothing, and left as a qualifier Micronaut would narrow by it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class AnyAnnotationTransformer implements NamedAnnotationTransformer {

    @Override
    public String getName() {
        return Cdi.ANY;
    }

    @Override
    public List<AnnotationValue<?>> transform(AnnotationValue<Annotation> annotation,
                                              VisitorContext visitorContext) {
        return List.of(AnnotationValue.builder("io.micronaut.cdi.annotation.CdiAny").build());
    }
}
