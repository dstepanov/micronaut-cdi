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
package io.micronaut.cdi.processor.extension;

import io.micronaut.cdi.lang.model.ast.ElementAnnotationInfo;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;

import java.util.List;

/**
 * An interceptor, described to an extension while its class is compiled: a bean that is an interceptor, which an
 * extension views as one through {@code asInterceptor()}, as it may not view any other bean.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementInterceptorInfo extends ElementBeanInfo implements InterceptorInfo {

    ElementInterceptorInfo(ClassElement declaringClass, VisitorContext context) {
        super(declaringClass, null, context);
    }

    @Override
    public boolean isInterceptor() {
        return true;
    }

    @Override
    public java.util.Collection<jakarta.enterprise.lang.model.AnnotationInfo> interceptorBindings() {
        java.util.List<jakarta.enterprise.lang.model.AnnotationInfo> bindings = new java.util.ArrayList<>();
        io.micronaut.core.annotation.AnnotationMetadata metadata = declaringClass.getAnnotationMetadata();
        for (String name : metadata.getAnnotationNamesByStereotype("jakarta.interceptor.InterceptorBinding")) {
            // every occurrence of a repeatable binding, as the container reports them
            List<io.micronaut.core.annotation.AnnotationValue<java.lang.annotation.Annotation>> occurrences =
                metadata.getAnnotationValuesByName(name);
            if (!occurrences.isEmpty()) {
                for (io.micronaut.core.annotation.AnnotationValue<?> occurrence : occurrences) {
                    bindings.add(new ElementAnnotationInfo(occurrence, context));
                }
                continue;
            }
            // a binding only a stereotype carries
            io.micronaut.core.annotation.AnnotationValue<?> value = metadata.getAnnotation(name);
            if (value != null) {
                bindings.add(new ElementAnnotationInfo(value, context));
            }
        }
        return bindings;
    }

    @Override
    public boolean intercepts(jakarta.enterprise.inject.spi.InterceptionType interceptionType) {
        String marker = switch (interceptionType) {
            case AROUND_INVOKE -> "jakarta.interceptor.AroundInvoke";
            case AROUND_CONSTRUCT -> "jakarta.interceptor.AroundConstruct";
            case POST_CONSTRUCT -> "jakarta.annotation.PostConstruct";
            case PRE_DESTROY -> "jakarta.annotation.PreDestroy";
            default -> null;
        };
        if (marker == null) {
            return false;
        }
        return declaringClass.getEnclosedElements(
                io.micronaut.inject.ast.ElementQuery.ALL_METHODS).stream()
            .anyMatch(method -> method.hasDeclaredAnnotation(marker));
    }
}
