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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;

import java.util.Arrays;

/**
 * Tells a class this processor compiles as a bean of the specification apart from a plain Micronaut class compiled
 * alongside it, which keeps Micronaut's own rules.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class SpecificationBeans {

    private SpecificationBeans() {
    }

    /**
     * Whether the class is subject to the rules of the specification: the scope {@code CdiScopeVisitor} resolved for
     * it - which a scope of Micronaut's own, such as {@code Refreshable}, is not - a stereotype, the interceptor
     * annotation, or a producer, an observer or a disposer it declares, each of which makes the class a bean
     * whatever else it declares. Asked after {@code CdiScopeVisitor} has visited the class.
     *
     * @param element The class
     * @return Whether it is a bean of the specification
     */
    static boolean isBeanOfTheSpecification(ClassElement element) {
        AnnotationMetadata metadata = element.getAnnotationMetadata();
        if (metadata.hasStereotype(CdiScope.class)
            || metadata.hasStereotype(Cdi.NORMAL_SCOPE)
            || metadata.hasStereotype(Cdi.STEREOTYPE)
            || metadata.hasStereotype("jakarta.interceptor.Interceptor")) {
            return true;
        }
        if (element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .anyMatch(field -> field.hasDeclaredAnnotation(Cdi.PRODUCES))) {
            return true;
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (method.hasDeclaredAnnotation(Cdi.PRODUCES) || Arrays.stream(method.getParameters()).anyMatch(
                parameter -> parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)
                    || parameter.hasDeclaredAnnotation(Cdi.DISPOSES))) {
                return true;
            }
        }
        return false;
    }
}
