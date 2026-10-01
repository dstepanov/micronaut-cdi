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
package io.micronaut.cdi.processor;

import io.micronaut.context.beans.definition.BeanDefinitionInjectionPoint;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.BeanDefinitionInjectionPointResolver;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.Collection;
import java.util.Optional;

/**
 * Resolves CDI Optional and collection injection as a bean of the full declared type.
 * The early Core resolver hook preserves ordinary Micronaut injection for other beans.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInjectionPointResolver implements BeanDefinitionInjectionPointResolver {
    @Override
    public Optional<BeanDefinitionInjectionPoint<ClassElement>> resolve(
        ClassElement beanType, ClassElement requestedType, AnnotationMetadata annotationMetadata,
        String parameterName, VisitorContext visitorContext) {
        // Proxy constructors contain a generated list of interceptor registrations. Those are Core's
        // dependencies, not CDI injection points, and must keep their built-in collection routing.
        if (parameterName.equals("$interceptors") || requestedType.getFirstTypeArgument()
            .map(type -> type.isAssignable(io.micronaut.context.BeanRegistration.class)).orElse(false)) {
            return Optional.empty();
        }
        if ((beanType.hasAnnotation("io.micronaut.cdi.internal.metadata.CdiScope")
            || beanType.hasStereotype("io.micronaut.cdi.internal.metadata.CdiScope"))
            && (requestedType.isAssignable(Optional.class) || requestedType.isAssignable(Collection.class))) {
            return Optional.of(new BeanDefinitionInjectionPoint.BeanInjectionPoint<>(requestedType, annotationMetadata));
        }
        return Optional.empty();
    }
}
