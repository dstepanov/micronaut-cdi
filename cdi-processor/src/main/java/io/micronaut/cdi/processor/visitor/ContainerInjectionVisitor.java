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
import io.micronaut.context.annotation.ResolveWith;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.utils.BeanInjectionUtils;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Selects exact CDI resolution for declared container injection points, before Core generates aggregation.
 * Only user members are annotated; generated proxy infrastructure retains its own resolution.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ContainerInjectionVisitor implements TypeElementVisitor<Object, Object> {
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 250;
    }

    @Override
    public void visitClass(ClassElement bean, VisitorContext context) {
        if (!bean.hasAnnotation(CdiScope.class) && !bean.hasStereotype(CdiScope.class)) {
            return;
        }
        bean.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .filter(ContainerInjectionVisitor::isInjected)
            .forEach(field -> mark(field, field.getGenericType()));
        BeanInjectionUtils.findBeanConstructor(bean).ifPresent(ContainerInjectionVisitor::markParameters);
        for (MethodElement method : bean.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (isInjected(method) || method.hasAnnotation(Cdi.PRODUCES)
                || java.util.Arrays.stream(method.getParameters()).anyMatch(parameter ->
                    parameter.hasAnnotation(Cdi.OBSERVES) || parameter.hasAnnotation(Cdi.OBSERVES_ASYNC)
                        || parameter.hasAnnotation(Cdi.DISPOSES))) {
                markParameters(method);
            }
        }
    }

    private static void markParameters(MethodElement method) {
        for (ParameterElement parameter : method.getParameters()) {
            if (!parameter.hasAnnotation(Cdi.OBSERVES) && !parameter.hasAnnotation(Cdi.OBSERVES_ASYNC)
                && !parameter.hasAnnotation(Cdi.DISPOSES)) {
                mark(parameter, parameter.getGenericType());
            }
        }
    }

    private static boolean isInjected(Element element) {
        return element.hasAnnotation(AnnotationUtil.INJECT) || element.hasAnnotation("jakarta.inject.Inject");
    }

    private static void mark(Element element, ClassElement type) {
        if (element.hasStereotype(ResolveWith.class) || type.getFirstTypeArgument()
            .map(argument -> argument.isAssignable(io.micronaut.context.BeanRegistration.class)).orElse(false)) {
            return;
        }
        if (type.isArray() || type.isAssignable(Optional.class) || type.isAssignable(Collection.class)
            || type.isAssignable(Iterable.class) || type.isAssignable(Map.class) || type.isAssignable(Stream.class)) {
            element.annotate(ResolveWith.class, builder -> builder.member("value", new AnnotationClassValue<>(
                "io.micronaut.cdi.internal.runtime.CdiBeanInjectionProvider")));
        }
    }
}
