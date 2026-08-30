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
package io.micronaut.cdi.reflection;

import io.micronaut.cdi.annotation.CdiProducer;
import io.micronaut.cdi.runtime.CdiBean;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One bean of the container, described to an extension in the terms of the specification.
 *
 * <p>What it reports is read from the bean definition Micronaut compiled, and the classes are read back where the
 * language model asks for a declaration rather than a name.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiBeanInfo implements BeanInfo {

    private final CdiBean<?> bean;
    private final BeanDefinition<?> definition;

    CdiBeanInfo(CdiBean<?> bean) {
        this.bean = bean;
        this.definition = bean.definition();
    }

    @Override
    public ScopeInfo scope() {
        Class<? extends Annotation> scope = bean.getScope();
        return new CdiScopeInfo(scope, scope.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class));
    }

    @Override
    public Collection<Type> types() {
        List<Type> types = new ArrayList<>();
        bean.getTypes().forEach(type -> types.add(ReflectionTypes.of(type)));
        return types;
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        List<AnnotationInfo> qualifiers = new ArrayList<>();
        for (Annotation qualifier : bean.getQualifiers()) {
            qualifiers.addAll(ReflectionAnnotations.of(new AnnotationCarrier(qualifier)));
        }
        return qualifiers;
    }

    @Override
    public ClassInfo declaringClass() {
        return new ReflectionClassInfo(producer() == null ? bean.getBeanClass() : declaringTypeOfTheProducer());
    }

    @Override
    public boolean isClassBean() {
        return producer() == null;
    }

    @Override
    public boolean isProducerMethod() {
        AnnotationValue<CdiProducer> producer = producer();
        return producer != null && !producer.booleanValue("field").orElse(false);
    }

    @Override
    public boolean isProducerField() {
        AnnotationValue<CdiProducer> producer = producer();
        return producer != null && producer.booleanValue("field").orElse(false);
    }

    @Override
    public boolean isSynthetic() {
        // a synthetic bean has no class of its own that was compiled, which is what having no producer and no
        // bean definition generated from a class amounts to
        return definition.getClass().getName().contains("RuntimeBeanDefinition");
    }

    @Override
    public @Nullable MethodInfo producerMethod() {
        if (!isProducerMethod()) {
            return null;
        }
        Class<?> declaring = declaringTypeOfTheProducer();
        String name = producerMember();
        for (Method method : declaring.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return new ReflectionMethodInfo(method, new ReflectionClassInfo(declaring));
            }
        }
        return null;
    }

    @Override
    public @Nullable FieldInfo producerField() {
        if (!isProducerField()) {
            return null;
        }
        Class<?> declaring = declaringTypeOfTheProducer();
        try {
            Field field = declaring.getDeclaredField(producerMember());
            return new ReflectionFieldInfo(field, new ReflectionClassInfo(declaring));
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    @Override
    public boolean isAlternative() {
        return bean.isAlternative();
    }

    @Override
    public @Nullable Integer priority() {
        return definition.getAnnotationMetadata().intValue("jakarta.annotation.Priority", "value")
            .stream().boxed().findFirst().orElse(null);
    }

    @Override
    public @Nullable String name() {
        return bean.getName();
    }

    @Override
    public @Nullable DisposerInfo disposer() {
        // the disposer of a produced bean was resolved while the producer was compiled, and is described by the
        // method it resolved to rather than by a model of it
        return null;
    }

    @Override
    public Collection<StereotypeInfo> stereotypes() {
        // a stereotype carries a scope, qualifiers and a name, each of which is reported on the bean itself
        return List.of();
    }

    @Override
    public Collection<InjectionPointInfo> injectionPoints() {
        // the injection points of a bean are resolved by Micronaut as it injects them, and are not described
        // again here
        return List.of();
    }

    private @Nullable AnnotationValue<CdiProducer> producer() {
        return definition.getAnnotation(CdiProducer.class);
    }

    private Class<?> declaringTypeOfTheProducer() {
        AnnotationValue<CdiProducer> producer = producer();
        return producer == null ? bean.getBeanClass()
            : producer.classValue("declaringType").orElse(bean.getBeanClass());
    }

    private String producerMember() {
        AnnotationValue<CdiProducer> producer = producer();
        return producer == null ? "" : producer.stringValue("member").orElse("");
    }

    @Override
    public String toString() {
        return "Bean[" + bean.getBeanClass().getName() + "]";
    }

    /**
     * Carries one annotation so that it can be read the way an annotated element is.
     *
     * @param annotation The annotation
     */
    private record AnnotationCarrier(Annotation annotation) implements java.lang.reflect.AnnotatedElement {

        @Override
        public <T extends Annotation> @Nullable T getAnnotation(Class<T> annotationClass) {
            return annotationClass.isInstance(annotation) ? annotationClass.cast(annotation) : null;
        }

        @Override
        public Annotation[] getAnnotations() {
            return new Annotation[]{annotation};
        }

        @Override
        public Annotation[] getDeclaredAnnotations() {
            return new Annotation[]{annotation};
        }
    }
}
