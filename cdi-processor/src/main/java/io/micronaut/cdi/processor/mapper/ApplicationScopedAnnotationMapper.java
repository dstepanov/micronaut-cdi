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

import io.micronaut.cdi.annotation.CdiApplicationScope;
import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads the application scope as the Micronaut scope of the same meaning.
 *
 * <p>An application scoped bean lives as long as the application does, which is what a Micronaut singleton does.
 * The specification also asks that a reference to it is a client proxy rather than the instance, since the scope
 * is a normal one; {@link CdiApplicationScope} is a singleton scope that is proxied for that reason.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ApplicationScopedAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.APPLICATION_SCOPED;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        // the annotation of the specification is kept beside the Micronaut one it was read as, so that the scope
        // a bean was written with is still what the container reports for it
        return List.of(
            annotation,
            AnnotationValue.builder(CdiApplicationScope.class).build(),
            AnnotationValue.builder(CdiScope.class).value(Cdi.APPLICATION_SCOPED).member("normal", true).build()
        );
    }
}
