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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what an extension says about one synthetic observer of section 2.10.5.
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiSyntheticObserverBuilder<T> implements SyntheticObserverBuilder<T> {

    private final Type eventType;
    private final List<Annotation> qualifiers = new ArrayList<>();
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private int priority = jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;
    private boolean async;
    private TransactionPhase transactionPhase = TransactionPhase.IN_PROGRESS;
    private @Nullable Class<? extends SyntheticObserver<T>> observer;

    CdiSyntheticObserverBuilder(Type eventType) {
        this.eventType = eventType;
    }

    /**
     * What the extension described.
     *
     * @return The observer
     */
    SyntheticObserverDescription<T> describe() {
        if (observer == null) {
            throw new IllegalStateException("The synthetic observer of " + eventType + " has no observer "
                + "class: an extension that adds one has to say what observes, with observeWith");
        }
        return new SyntheticObserverDescription<>(eventType, List.copyOf(qualifiers), priority, async,
            transactionPhase, Map.copyOf(parameters), observer);
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(Class<?> declaringClass) {
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(ClassInfo declaringClass) {
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Class<? extends Annotation> annotationType) {
        qualifiers.add(io.micronaut.cdi.runtime.CdiAnnotations.annotationOf(annotationType,
            io.micronaut.core.annotation.AnnotationValue.builder(annotationType.getName()).build()));
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(AnnotationInfo annotation) {
        throw new UnsupportedOperationException("A qualifier of a synthetic observer is given as an "
            + "annotation here");
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Annotation annotation) {
        qualifiers.add(annotation);
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> async(boolean async) {
        this.async = async;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> transactionPhase(TransactionPhase transactionPhase) {
        this.transactionPhase = transactionPhase;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, boolean value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, boolean[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, int value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, int[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, long value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, long[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, double value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, double[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, String value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, String[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Enum<?> value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Enum<?>[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Class<?> value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, ClassInfo value) {
        throw new UnsupportedOperationException("A parameter of a synthetic observer is given as a class here");
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Class<?>[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, ClassInfo[] value) {
        throw new UnsupportedOperationException("A parameter of a synthetic observer is given as a class here");
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo value) {
        return param(key, LangModelAnnotations.annotationOf(value,
            Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader() : getClass().getClassLoader()));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Annotation value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo[] value) {
        java.lang.annotation.Annotation[] annotations = new java.lang.annotation.Annotation[value.length];
        ClassLoader loader = Thread.currentThread().getContextClassLoader() != null
            ? Thread.currentThread().getContextClassLoader() : getClass().getClassLoader();
        for (int i = 0; i < value.length; i++) {
            annotations[i] = LangModelAnnotations.annotationOf(value[i], loader);
        }
        return param(key, annotations);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Annotation[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, jakarta.enterprise.inject.build.compatible.spi.InvokerInfo value) {
        // an invoker built by the registration phase is itself the invocable (RecordedInvoker), and rides
        // along like any other value
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, jakarta.enterprise.inject.build.compatible.spi.InvokerInfo[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticObserverBuilder<T> observeWith(Class<? extends SyntheticObserver<T>> observerClass) {
        this.observer = observerClass;
        return this;
    }

    private SyntheticObserverBuilder<T> param(String key, Object value) {
        parameters.put(key, value);
        return this;
    }
}
