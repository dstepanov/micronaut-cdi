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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.CdiScopes;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads the dependent pseudo-scope as the Micronaut scope of the same meaning.
 *
 * <p>A dependent bean belongs to whatever it was injected into and is created afresh for every injection point, as
 * a Micronaut prototype is. What the specification asks for beyond that — that such an instance is destroyed with
 * the bean it was injected into — is what Micronaut calls a dependent bean too, and it does it already.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class DependentAnnotationMapper implements NamedAnnotationMapper {

    @Override
    public String getName() {
        return Cdi.DEPENDENT;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        // Micronaut keeps the annotation of the specification beside what it is read as; being annotated
        // jakarta.inject.Scope itself it would leave the bean declaring a second scope that has no context to be
        // resolved in, so the scope visitor removes it (CdiScopeVisitor). What it was written with is recorded here
        return CdiScopes.annotationsOf(Cdi.DEPENDENT, false);
    }
}
