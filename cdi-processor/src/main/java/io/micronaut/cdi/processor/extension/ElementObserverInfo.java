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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.List;

/**
 * An observer method as the registration phase of section 2.10.3 describes it, read straight off the compiled
 * elements: the method, the parameter it observes, and the class that declares them.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementObserverInfo implements ObserverInfo {

    private final ClassElement declaringClass;
    private final MethodElement method;
    private final ParameterElement observed;
    private final boolean async;

    ElementObserverInfo(ClassElement declaringClass, MethodElement method, ParameterElement observed,
                        boolean async) {
        this.declaringClass = declaringClass;
        this.method = method;
        this.observed = observed;
        this.async = async;
    }

    /**
     * The element of the parameter the method observes, which is what the registration filter reads.
     *
     * @return The observed parameter
     */
    ParameterElement observedParameter() {
        return observed;
    }

    @Override
    public Type eventType() {
        return ElementTypes.of(observed.getGenericType());
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        ClassInfo declaring = new ElementClassInfo(declaringClass);
        return new ElementMethodInfo(method, declaring).parameters().stream()
            .filter(parameter -> parameter.name().equals(observed.getName()))
            .findFirst()
            .map(parameter -> parameter.annotations(annotation ->
                annotation.declaration().hasAnnotation(jakarta.inject.Qualifier.class)))
            .orElse(List.of());
    }

    @Override
    public ClassInfo declaringClass() {
        return new ElementClassInfo(declaringClass);
    }

    @Override
    public MethodInfo observerMethod() {
        return new ElementMethodInfo(method, new ElementClassInfo(declaringClass));
    }

    @Override
    public ParameterInfo eventParameter() {
        return observerMethod().parameters().stream()
            .filter(parameter -> parameter.name().equals(observed.getName()))
            .findFirst()
            .orElseThrow();
    }

    @Override
    public BeanInfo bean() {
        return new ElementBeanInfo(declaringClass, null);
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    @Override
    public int priority() {
        return observed.intValue(jakarta.annotation.Priority.class).orElse(
            jakarta.interceptor.Interceptor.Priority.APPLICATION + 500);
    }

    @Override
    public boolean isAsync() {
        return async;
    }

    @Override
    public Reception reception() {
        String value = async
            ? observed.stringValue("jakarta.enterprise.event.ObservesAsync", "notifyObserver").orElse("ALWAYS")
            : observed.stringValue("jakarta.enterprise.event.Observes", "notifyObserver").orElse("ALWAYS");
        return Reception.valueOf(value);
    }

    @Override
    public TransactionPhase transactionPhase() {
        if (async) {
            return TransactionPhase.IN_PROGRESS;
        }
        return TransactionPhase.valueOf(
            observed.stringValue("jakarta.enterprise.event.Observes", "during").orElse("IN_PROGRESS"));
    }
}
