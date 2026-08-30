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
package io.micronaut.cdi.runtime;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A static observer method, dispatched reflectively: Micronaut generates no executable method for a static
 * method, so the compiler records it on the class and it is read back here.
 *
 * @param <T> The observed event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class StaticObserverMethod<T> implements ObserverMethod<T>, CdiNotifiable {

    private final BeanContext beanContext;
    private final BeanDefinition<?> declaring;
    private final Method method;
    private final int observedParameter;
    private final boolean async;
    private final int priority;
    private final String during;

    StaticObserverMethod(BeanContext beanContext, BeanDefinition<?> declaring, Method method,
                         int observedParameter, boolean async, int priority, String during) {
        this.beanContext = beanContext;
        this.declaring = declaring;
        this.method = method;
        this.observedParameter = observedParameter;
        this.async = async;
        this.priority = priority;
        this.during = during;
    }

    @Override
    public Class<?> getBeanClass() {
        return method.getDeclaringClass();
    }

    @Override
    public Type getObservedType() {
        return method.getGenericParameterTypes()[observedParameter];
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        Set<Annotation> qualifiers = new LinkedHashSet<>();
        for (Annotation annotation : method.getParameterAnnotations()[observedParameter]) {
            if (annotation.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers;
    }

    @Override
    public Reception getReception() {
        return Reception.ALWAYS;
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return TransactionPhase.valueOf(during);
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isAsync() {
        return async;
    }

    @Override
    public jakarta.enterprise.inject.spi.Bean<?> getDeclaringBean() {
        return beanContext.getBean(CdiBeanContainer.class).canonicalBean(declaring);
    }

    @Override
    public void notify(T event) {
        notifyWith(event, new CdiEventMetadata(getObservedQualifiers(), null, getObservedType()));
    }

    @Override
    public void notifyWith(Object event, EventMetadata metadata) {
        Object[] parameters = new Object[method.getParameterCount()];
        // the auxiliary parameters resolve the way injection points do — by type and by the qualifiers written
        // on them — and a dependent instance among them lives only as long as the notification
        CdiInstance<Object> lookup = new CdiInstance<>(beanContext, io.micronaut.core.type.Argument.OBJECT_ARGUMENT);
        try {
            for (int i = 0; i < parameters.length; i++) {
                if (i == observedParameter) {
                    parameters[i] = event;
                } else if (method.getParameterTypes()[i] == EventMetadata.class) {
                    parameters[i] = metadata;
                } else {
                    parameters[i] = lookup
                        .selectArgument(
                            io.micronaut.core.type.Argument.of(method.getGenericParameterTypes()[i]),
                            qualifiersOf(method.getParameterAnnotations()[i]))
                        .get();
                }
            }
            invoke(parameters);
        } finally {
            lookup.destroyTransients();
        }
    }

    private static java.lang.annotation.Annotation[] qualifiersOf(java.lang.annotation.Annotation[] written) {
        java.util.List<java.lang.annotation.Annotation> qualifiers = new java.util.ArrayList<>(written.length);
        for (java.lang.annotation.Annotation annotation : written) {
            if (ExtensionQualifiers.isQualifier(annotation.annotationType())) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers.toArray(new java.lang.annotation.Annotation[0]);
    }

    private void invoke(Object[] parameters) {
        try {
            method.setAccessible(true);
            method.invoke(null, parameters);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new jakarta.enterprise.event.ObserverException(
                cause == null ? e.getMessage() : cause.getMessage(), cause == null ? e : cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("The static observer method " + method + " is not accessible", e);
        }
    }

    @Override
    public String toString() {
        return "Observer[" + method.getDeclaringClass().getName() + "#" + method.getName() + " (static)]";
    }
}
