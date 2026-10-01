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

import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.cdi.annotation.CdiSyntheticParameter;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
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
 * Records what an extension says about one synthetic bean of section 2.10.5, in the terms the compilation
 * works in: a class is the class the compiler sees, and a qualifier or a parameter is the annotation value it
 * will be written as.
 *
 * <p>Nothing here exists at runtime. What is gathered is described to the registration phase as a
 * {@link SyntheticBeanInfo} and written as the annotation metadata of a bean definition generated for the
 * creator class, which is all the running container reads.</p>
 *
 * @param <T> The type of the bean
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class RecordingBeanBuilder<T> implements SyntheticBeanBuilder<T> {

    private final VisitorContext context;
    private final String id;
    private final ClassElement implementation;
    private final List<ClassElement> types = new ArrayList<>();
    private final List<AnnotationValue<?>> qualifiers = new ArrayList<>();
    private final Map<String, AnnotationValue<CdiSyntheticParameter>> parameters = new LinkedHashMap<>();
    private final List<ClassElement> stereotypes = new ArrayList<>();
    private @Nullable ClassElement scope;
    private @Nullable String name;
    private @Nullable Integer priority;
    private boolean alternative;
    private @Nullable ClassElement creator;
    private @Nullable ClassElement disposer;

    RecordingBeanBuilder(VisitorContext context, String id, Class<T> implementationClass) {
        this.context = context;
        this.id = id;
        this.implementation = SyntheticRecords.classElement(context, implementationClass,
            "implementation class of a synthetic bean");
    }

    String id() {
        return id;
    }

    ClassElement implementation() {
        return implementation;
    }

    /**
     * The bean types the extension declared, or the {@code Object} alone that the API defaults to: the
     * implementation class is not among them unless the extension said so.
     */
    List<ClassElement> types() {
        if (types.isEmpty()) {
            return List.of(SyntheticRecords.classElement(context, Object.class, "bean type"));
        }
        return types;
    }

    List<AnnotationValue<?>> qualifiers() {
        return qualifiers;
    }

    List<AnnotationValue<CdiSyntheticParameter>> parameters() {
        return List.copyOf(parameters.values());
    }

    List<ClassElement> stereotypes() {
        return stereotypes;
    }

    @Nullable ClassElement scope() {
        return scope;
    }

    @Nullable String name() {
        return name;
    }

    @Nullable Integer priority() {
        return priority;
    }

    boolean alternative() {
        return alternative;
    }

    ClassElement creator() {
        if (creator == null) {
            throw new IllegalStateException("The synthetic bean " + implementation.getName() + " has no "
                + "creator: an extension that adds a bean has to say what creates it, with createWith");
        }
        return creator;
    }

    @Nullable ClassElement disposer() {
        return disposer;
    }

    @Override
    public SyntheticBeanBuilder<T> type(Class<?> type) {
        types.add(SyntheticRecords.classElement(context, type, "bean type"));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> type(ClassInfo type) {
        types.add(SyntheticRecords.classElement(context, type.name(), "bean type"));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> type(jakarta.enterprise.lang.model.types.Type type) {
        // a bean type is resolved by its class here, which is what the definition registered for the bean
        // exposes; the arguments of a parameterized type are not part of what it is resolved by
        types.add(ElementTypes.elementOf(type));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(Class<? extends Annotation> annotationType) {
        qualifiers.add(SyntheticRecords.annotationOf(context, annotationType));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(AnnotationInfo qualifierAnnotation) {
        qualifiers.add(SyntheticRecords.annotationOf(context, qualifierAnnotation));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> qualifier(Annotation qualifierAnnotation) {
        qualifiers.add(SyntheticRecords.annotationOf(qualifierAnnotation));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> scope(Class<? extends Annotation> scopeAnnotation) {
        this.scope = SyntheticRecords.classElement(context, scopeAnnotation, "scope");
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
        stereotypes.add(SyntheticRecords.classElement(context, stereotypeAnnotation, "stereotype"));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> stereotype(ClassInfo stereotypeAnnotation) {
        stereotypes.add(SyntheticRecords.classElement(context, stereotypeAnnotation.name(), "stereotype"));
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> createWith(Class<? extends SyntheticBeanCreator<T>> creatorClass) {
        this.creator = SyntheticRecords.classElement(context, creatorClass, "creator");
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> disposeWith(Class<? extends SyntheticBeanDisposer<T>> disposerClass) {
        this.disposer = SyntheticRecords.classElement(context, disposerClass, "disposer");
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, boolean value) {
        return param(key, SyntheticRecords.booleans(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, boolean[] value) {
        return param(key, SyntheticRecords.booleans(key, true, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, int value) {
        return param(key, SyntheticRecords.ints(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, int[] value) {
        return param(key, SyntheticRecords.ints(key, true, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, long value) {
        return param(key, SyntheticRecords.longs(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, long[] value) {
        return param(key, SyntheticRecords.longs(key, true, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, double value) {
        return param(key, SyntheticRecords.doubles(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, double[] value) {
        return param(key, SyntheticRecords.doubles(key, true, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, String value) {
        return param(key, SyntheticRecords.strings(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, String[] value) {
        return param(key, SyntheticRecords.strings(key, true, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Enum<?> value) {
        return param(key, SyntheticRecords.enums(key, false, value.getDeclaringClass(), value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Enum<?>[] value) {
        return param(key, SyntheticRecords.enums(key, true, value.getClass().getComponentType(), value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Class<?> value) {
        return param(key, SyntheticRecords.classes(key, false, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, ClassInfo value) {
        return param(key, SyntheticRecords.classes(key, false, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Class<?>[] value) {
        return param(key, SyntheticRecords.classes(key, true, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, ClassInfo[] value) {
        return param(key, SyntheticRecords.classes(key, true, SyntheticRecords.namesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo value) {
        return param(key, SyntheticRecords.annotations(key, false, SyntheticRecords.valuesOf(context, value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Annotation value) {
        return param(key, SyntheticRecords.annotations(key, false, SyntheticRecords.valuesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo[] value) {
        return param(key, SyntheticRecords.annotations(key, true, SyntheticRecords.valuesOf(context, value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, Annotation[] value) {
        return param(key, SyntheticRecords.annotations(key, true, SyntheticRecords.valuesOf(value)));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, InvokerInfo value) {
        return param(key, SyntheticRecords.invokers(key, false, value));
    }

    @Override
    public SyntheticBeanBuilder<T> withParam(String key, InvokerInfo[] value) {
        return param(key, SyntheticRecords.invokers(key, true, value));
    }

    private SyntheticBeanBuilder<T> param(String key, AnnotationValue<CdiSyntheticParameter> value) {
        parameters.put(key, value);
        return this;
    }
}
