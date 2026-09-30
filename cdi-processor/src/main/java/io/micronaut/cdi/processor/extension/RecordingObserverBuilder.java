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

import io.micronaut.cdi.annotation.CdiRecordedType;
import io.micronaut.cdi.annotation.CdiSyntheticParameter;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what an extension says about one synthetic observer of section 2.10.5, in the terms the compilation
 * works in.
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class RecordingObserverBuilder<T> implements SyntheticObserverBuilder<T> {

    private final VisitorContext context;
    private final String id;
    private final Type eventType;
    private final AnnotationValue<CdiRecordedType> recordedEventType;
    private final List<AnnotationValue<?>> qualifiers = new ArrayList<>();
    private final Map<String, AnnotationValue<CdiSyntheticParameter>> parameters = new LinkedHashMap<>();
    private @Nullable ClassElement declaringClass;
    private int priority = jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;
    private boolean async;
    private TransactionPhase transactionPhase = TransactionPhase.IN_PROGRESS;
    private @Nullable ClassElement observer;

    RecordingObserverBuilder(VisitorContext context, String id, Type eventType,
                             AnnotationValue<CdiRecordedType> recordedEventType,
                             @Nullable ClassElement declaringClass) {
        this.context = context;
        this.id = id;
        this.eventType = eventType;
        this.recordedEventType = recordedEventType;
        this.declaringClass = declaringClass;
    }

    String id() {
        return id;
    }

    Type eventType() {
        return eventType;
    }

    AnnotationValue<CdiRecordedType> recordedEventType() {
        return recordedEventType;
    }

    List<AnnotationValue<?>> qualifiers() {
        return qualifiers;
    }

    List<AnnotationValue<CdiSyntheticParameter>> parameters() {
        return List.copyOf(parameters.values());
    }

    /**
     * The class the observer is reported as declared by: the one the extension named, or the extension itself.
     */
    ClassElement declaringClass() {
        if (declaringClass == null) {
            throw new IllegalStateException("The synthetic observer of " + eventType + " names no declaring "
                + "class, and the extension that declares it is not on the classpath of the compilation");
        }
        return declaringClass;
    }

    int priority() {
        return priority;
    }

    boolean async() {
        return async;
    }

    TransactionPhase transactionPhase() {
        return transactionPhase;
    }

    ClassElement observer() {
        if (observer == null) {
            throw new IllegalStateException("The synthetic observer of " + eventType + " has no observer "
                + "class: an extension that adds one has to say what observes, with observeWith");
        }
        return observer;
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(Class<?> declaringClass) {
        this.declaringClass = SyntheticRecords.classElement(context, declaringClass, "declaring class");
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(ClassInfo declaringClass) {
        this.declaringClass = SyntheticRecords.classElement(context, declaringClass.name(), "declaring class");
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Class<? extends Annotation> annotationType) {
        qualifiers.add(SyntheticRecords.annotationOf(context, annotationType));
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(AnnotationInfo annotation) {
        qualifiers.add(SyntheticRecords.annotationOf(context, annotation));
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Annotation annotation) {
        qualifiers.add(SyntheticRecords.annotationOf(annotation));
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
    public SyntheticObserverBuilder<T> observeWith(Class<? extends SyntheticObserver<T>> observerClass) {
        this.observer = SyntheticRecords.classElement(context, observerClass, "synthetic observer");
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, boolean value) {
        return param(key, SyntheticRecords.booleans(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, boolean[] value) {
        return param(key, SyntheticRecords.booleans(key, true, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, int value) {
        return param(key, SyntheticRecords.ints(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, int[] value) {
        return param(key, SyntheticRecords.ints(key, true, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, long value) {
        return param(key, SyntheticRecords.longs(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, long[] value) {
        return param(key, SyntheticRecords.longs(key, true, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, double value) {
        return param(key, SyntheticRecords.doubles(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, double[] value) {
        return param(key, SyntheticRecords.doubles(key, true, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, String value) {
        return param(key, SyntheticRecords.strings(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, String[] value) {
        return param(key, SyntheticRecords.strings(key, true, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Enum<?> value) {
        return param(key, SyntheticRecords.enums(key, false, value.getDeclaringClass(), value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Enum<?>[] value) {
        return param(key, SyntheticRecords.enums(key, true, value.getClass().getComponentType(), value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Class<?> value) {
        return param(key, SyntheticRecords.classes(key, false, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, ClassInfo value) {
        return param(key, SyntheticRecords.classes(key, false, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Class<?>[] value) {
        return param(key, SyntheticRecords.classes(key, true, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, ClassInfo[] value) {
        return param(key, SyntheticRecords.classes(key, true, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo value) {
        return param(key, SyntheticRecords.annotations(key, false, SyntheticRecords.valuesOf(context, value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Annotation value) {
        return param(key, SyntheticRecords.annotations(key, false, SyntheticRecords.valuesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo[] value) {
        return param(key, SyntheticRecords.annotations(key, true, SyntheticRecords.valuesOf(context, value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, Annotation[] value) {
        return param(key, SyntheticRecords.annotations(key, true, SyntheticRecords.valuesOf(value)));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo value) {
        return param(key, SyntheticRecords.invokers(key, false, value));
    }

    @Override
    public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo[] value) {
        return param(key, SyntheticRecords.invokers(key, true, value));
    }

    private SyntheticObserverBuilder<T> param(String key, AnnotationValue<CdiSyntheticParameter> value) {
        parameters.put(key, value);
        return this;
    }
}
