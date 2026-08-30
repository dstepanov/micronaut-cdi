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
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Makes an annotation that declares itself a normal scope into one Micronaut treats as a scope.
 *
 * <p>The two scopes the specification names — application and request — are read by their own mappers into the
 * Micronaut scopes this module provides. What this covers is the scope an application declares for itself, by
 * annotating an annotation {@code jakarta.enterprise.context.NormalScope}: the mapper runs wherever that
 * meta-annotation appears, so the declared annotation becomes a bean-defining one, and a bean carrying it is
 * proxied and held the way a normal scoped bean is.</p>
 *
 * <p>The instances of such a scope are held for as long as the container runs, which is the widest reading of a
 * scope nothing else manages: the specification leaves the lifecycle of a custom scope to whoever registered its
 * context, and CDI Lite registers none. Which annotation the bean was written with is recorded separately, by
 * {@link io.micronaut.cdi.processor.visitor.CdiScopeVisitor}, so the container still reports the scope the
 * author named.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NormalScopeAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.NORMAL_SCOPE;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(
            annotation,
            AnnotationValue.builder(Bean.class).build(),
            AnnotationValue.builder(CdiApplicationScope.class).build()
        );
    }
}
