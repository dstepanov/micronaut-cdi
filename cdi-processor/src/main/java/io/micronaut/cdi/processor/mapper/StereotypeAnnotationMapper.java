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
import io.micronaut.context.annotation.DefaultScope;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Makes a stereotype a bean-defining annotation, which section 2.1.8 says it is.
 *
 * <p>A stereotype may declare a scope, and then the bean is in it; one that declares none leaves the bean in the
 * dependent pseudo-scope, which is what the default scope here says. The default loses to any scope the
 * stereotype or the bean declares, so a stereotype that says more is not overruled.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class StereotypeAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.STEREOTYPE;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(
            annotation,
            AnnotationValue.builder(Bean.class).build(),
            AnnotationValue.builder(DefaultScope.class).value(Prototype.class).build()
        );
    }
}
