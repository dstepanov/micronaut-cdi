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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A synthetic bean as the registration phase of section 2.10.4 is told about it: described from what the
 * extension recorded in the synthesis phase of the same compilation, in the language model the compiler's view
 * of the classes gives.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SyntheticBeanInfo implements BeanInfo {

    private final RecordingBeanBuilder<?> bean;
    private final SynthesisPhase.Scope scope;
    private final boolean alternative;

    SyntheticBeanInfo(RecordingBeanBuilder<?> bean, SynthesisPhase.Scope scope, boolean alternative) {
        this.bean = bean;
        this.scope = scope;
        this.alternative = alternative;
    }

    /**
     * The class the bean is created as, which is what a registration method's types are matched against.
     */
    ClassElement beanType() {
        return bean.implementation();
    }

    @Override
    public ScopeInfo scope() {
        return new ElementScopeInfo(scope.name(), scope.normal());
    }

    @Override
    public Collection<Type> types() {
        List<Type> types = new ArrayList<>();
        boolean object = false;
        for (ClassElement type : bean.types()) {
            object = object || "java.lang.Object".equals(type.getName());
            types.add(ElementTypes.of(type));
        }
        if (!object) {
            // every bean has Object among its types (section 2.2)
            types.add(ElementTypes.objectType());
        }
        return types;
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        List<AnnotationInfo> qualifiers = new ArrayList<>();
        qualifiers.add(new ElementAnnotationInfo(AnnotationValue.builder("jakarta.enterprise.inject.Any").build()));
        if (bean.qualifiers().isEmpty()) {
            qualifiers.add(new ElementAnnotationInfo(
                AnnotationValue.builder("jakarta.enterprise.inject.Default").build()));
        }
        for (AnnotationValue<?> qualifier : bean.qualifiers()) {
            qualifiers.add(new ElementAnnotationInfo(qualifier));
        }
        return qualifiers;
    }

    @Override
    public ClassInfo declaringClass() {
        return new ElementClassInfo(bean.implementation());
    }

    @Override
    public boolean isClassBean() {
        return false;
    }

    @Override
    public boolean isProducerMethod() {
        return false;
    }

    @Override
    public boolean isProducerField() {
        return false;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }

    @Override
    public @Nullable MethodInfo producerMethod() {
        return null;
    }

    @Override
    public @Nullable FieldInfo producerField() {
        return null;
    }

    @Override
    public boolean isAlternative() {
        return alternative;
    }

    @Override
    public @Nullable Integer priority() {
        return bean.priority();
    }

    @Override
    public @Nullable String name() {
        return bean.name();
    }

    @Override
    public @Nullable DisposerInfo disposer() {
        // the disposal function of a synthetic bean is a class the extension named, not a disposer method
        return null;
    }

    @Override
    public Collection<StereotypeInfo> stereotypes() {
        // a stereotype carries a scope, qualifiers and a name, each of which is reported on the bean itself
        return List.of();
    }

    @Override
    public Collection<InjectionPointInfo> injectionPoints() {
        // a synthetic bean is created by a function, and has no injection point
        return List.of();
    }

    @Override
    public String toString() {
        return "Bean[" + bean.implementation().getName() + ", synthetic]";
    }
}
