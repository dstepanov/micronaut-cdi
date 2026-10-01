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
import io.micronaut.cdi.lang.model.ast.ElementClassInfo;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A synthetic observer as the registration phase of section 2.10.4 is told about it: described from what the
 * extension recorded in the synthesis phase of the same compilation.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SyntheticObserverInfo implements ObserverInfo {

    private final RecordingObserverBuilder<?> observer;

    SyntheticObserverInfo(RecordingObserverBuilder<?> observer) {
        this.observer = observer;
    }

    @Override
    public Type eventType() {
        return observer.eventType();
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        List<AnnotationInfo> qualifiers = new ArrayList<>();
        for (AnnotationValue<?> qualifier : observer.qualifiers()) {
            qualifiers.add(new ElementAnnotationInfo(qualifier));
        }
        return qualifiers;
    }

    @Override
    public ClassInfo declaringClass() {
        return new ElementClassInfo(observer.declaringClass());
    }

    @Override
    public @Nullable MethodInfo observerMethod() {
        return null;
    }

    @Override
    public @Nullable ParameterInfo eventParameter() {
        return null;
    }

    @Override
    public @Nullable BeanInfo bean() {
        return null;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }

    @Override
    public int priority() {
        return observer.priority();
    }

    @Override
    public boolean isAsync() {
        return observer.async();
    }

    @Override
    public Reception reception() {
        return Reception.ALWAYS;
    }

    @Override
    public TransactionPhase transactionPhase() {
        return observer.transactionPhase();
    }

    @Override
    public String toString() {
        return "Observer[" + observer.eventType() + ", synthetic]";
    }
}
