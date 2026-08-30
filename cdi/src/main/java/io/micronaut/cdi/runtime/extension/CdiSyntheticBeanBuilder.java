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
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a bean an extension wants the container to have.
 *
 * <p>Everything the builder is told is gathered here and read once the extension has finished, which is what
 * {@link SynthesisRunner} then registers with Micronaut.</p>
 *
 * <p>The parts of the builder written in terms of the language model rather than of classes — a qualifier given
 * as an {@code AnnotationInfo}, a type given as a {@code ClassInfo} — are refused rather than approximated: this
 * phase runs as the container starts, where the language model of the build is no longer around.</p>
 *
 * @param <T> The type of the bean
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiSyntheticBeanBuilder<T> implements SyntheticBeanBuilder<T> {

    private final ClassLoader deploymentLoader;

    private final Class<T> implementationClass;
    private final List<Class<?>> types = new ArrayList<>();
    private final List<Annotation> qualifiers = new ArrayList<>();
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private @Nullable Class<? extends Annotation> scope;
    private @Nullable String name;
    private @Nullable Integer priority;
    private boolean alternative;
    private final List<Class<? extends Annotation>> stereotypes = new ArrayList<>();
    private @Nullable Class<? extends SyntheticBeanCreator<T>> creator;
    private @Nullable Class<? extends SyntheticBeanDisposer<T>> disposer;

    CdiSyntheticBeanBuilder(Class<T> implementationClass, ClassLoader deploymentLoader) {
        this.deploymentLoader = deploymentLoader;
        this.implementationClass = implementationClass;
    }

    /**
     * What the extension described.
     *
     * @return The bean
     */
    SyntheticBean<T> describe() {
        if (creator == null) {
            throw new IllegalStateException("The synthetic bean " + implementationClass.getName() + " has no "
                + "creator: an extension that adds a bean has to say what creates it, with createWith");
        }
        // the API's default: a bean that declared no type has the set {Object}, not its implementation class
        List<Class<?>> beanTypes = types.isEmpty() ? List.of(Object.class) : List.copyOf(types);
        return new SyntheticBean<>(implementationClass, beanTypes, List.copyOf(qualifiers), scope, name, priority,
            alternative, List.copyOf(stereotypes), Map.copyOf(parameters), creator, disposer);
    }

    @Override
    public SyntheticBeanBuilder<T> type(Class<?> type) {
        types.add(type);
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> type(ClassInfo type) {
        throw new UnsupportedOperationException("A type of a synthetic bean is named by its class here: the "
            + "language model of the build is not around while the container is starting");
    }

    @Override
    public SyntheticBeanBuilder<T> type(jakarta.enterprise.lang.model.types.Type type) {
        throw new UnsupportedOperationException("A type of a synthetic bean is named by its class here: the "
            + "language model of the build is not around while the container is starting");
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(Class<? extends Annotation> annotationType) {
        qualifiers.add(CdiAnnotationLiterals.of(annotationType));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(AnnotationInfo qualifierAnnotation) {
        throw new UnsupportedOperationException("A qualifier of a synthetic bean is given as an annotation here: "
            + "the language model of the build is not around while the container is starting");
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(Annotation qualifierAnnotation) {
        qualifiers.add(qualifierAnnotation);
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> scope(Class<? extends Annotation> scopeAnnotation) {
        this.scope = scopeAnnotation;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> alternative(boolean isAlternative) {
        this.alternative = isAlternative;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> name(String beanName) {
        this.name = beanName;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> stereotype(Class<? extends Annotation> stereotypeAnnotation) {
        stereotypes.add(stereotypeAnnotation);
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public SyntheticBeanBuilder<T> stereotype(ClassInfo stereotypeAnnotation) {
        ClassLoader loader = deploymentLoader;
        try {
            stereotypes.add((Class<? extends Annotation>) Class.forName(
                stereotypeAnnotation.name(), false, loader));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The stereotype " + stereotypeAnnotation.name()
                + " is not on the classpath", e);
        }
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> createWith(Class<? extends SyntheticBeanCreator<T>> creatorClass) {
        this.creator = creatorClass;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> disposeWith(Class<? extends SyntheticBeanDisposer<T>> disposerClass) {
        this.disposer = disposerClass;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, boolean value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, boolean[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, int value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, int[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, long value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, long[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, double value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, double[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, String value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, String[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Enum<?> value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Enum<?>[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Class<?> value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, ClassInfo value) {
        throw new UnsupportedOperationException("A parameter of a synthetic bean is given as a class here");
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Class<?>[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, ClassInfo[] value) {
        throw new UnsupportedOperationException("A parameter of a synthetic bean is given as a class here");
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo value) {
        return param(key, LangModelAnnotations.annotationOf(value,
            deploymentLoader));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Annotation value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo[] value) {
        java.lang.annotation.Annotation[] annotations = new java.lang.annotation.Annotation[value.length];
        ClassLoader loader = deploymentLoader;
        for (int i = 0; i < value.length; i++) {
            annotations[i] = LangModelAnnotations.annotationOf(value[i], loader);
        }
        return param(key, annotations);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Annotation[] value) {
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, jakarta.enterprise.inject.build.compatible.spi.InvokerInfo value) {
        // an invoker built by the registration phase is itself the invocable (RecordedInvoker), and rides
        // along like any other value
        return param(key, value);
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, jakarta.enterprise.inject.build.compatible.spi.InvokerInfo[] value) {
        return param(key, value);
    }

    private SyntheticBeanBuilder<T> param(String key, Object value) {
        parameters.put(key, value);
        return this;
    }
}
