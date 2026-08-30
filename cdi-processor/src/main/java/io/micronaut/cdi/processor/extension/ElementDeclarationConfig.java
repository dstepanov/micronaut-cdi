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
import io.micronaut.inject.ast.Element;
import jakarta.enterprise.inject.build.compatible.spi.DeclarationConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;

import java.lang.annotation.Annotation;
import java.util.function.Predicate;

/**
 * What an extension enhancing a declaration does to it: the annotations it adds and the ones it takes away.
 *
 * <p>An enhancement happens while the class is being compiled, so what it changes is what the compiler goes on
 * to read. Adding an annotation here is the same thing as having written it on the declaration, and taking one
 * away is the same as not having written it: the bean definition Micronaut generates afterwards is generated
 * from what the enhancement left behind.</p>
 *
 * @param <C> The kind of configuration this is, which each subclass returns from the methods that add and remove
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public abstract class ElementDeclarationConfig<C extends DeclarationConfig> implements DeclarationConfig {

    private final Element element;

    protected ElementDeclarationConfig(Element element) {
        this.element = element;
    }

    /**
     * This configuration, as the kind the caller asked for.
     *
     * @return This
     */
    protected abstract C self();

    @Override
    public abstract DeclarationInfo info();

    @Override
    public C addAnnotation(Class<? extends Annotation> annotationType) {
        element.annotate(annotationType.getName());
        return self();
    }

    @Override
    public C addAnnotation(AnnotationInfo annotation) {
        if (annotation instanceof ElementAnnotationInfo info) {
            element.annotate(info.annotationValue());
        } else {
            element.annotate(annotation.name());
        }
        return self();
    }

    @Override
    public C addAnnotation(Annotation annotation) {
        element.annotate(ExtensionAnnotationValues.of(annotation));
        return self();
    }

    @Override
    public C removeAnnotation(Predicate<AnnotationInfo> predicate) {
        java.util.List<String> removed = new java.util.ArrayList<>();
        for (String name : declaredAnnotationNames()) {
            AnnotationValue<Annotation> annotation = element.getDeclaredAnnotation(name);
            if (annotation == null) {
                annotation = element.getAnnotationMetadata().getDeclaredMetadata().getAnnotation(name);
            }
            if (annotation != null && predicate.test(new ElementAnnotationInfo(annotation))) {
                element.removeAnnotation(name);
                removed.add(name);
            }
        }
        recordRemoved(removed);
        return self();
    }

    @Override
    public C removeAllAnnotations() {
        java.util.List<String> removed = new java.util.ArrayList<>(declaredAnnotationNames());
        for (String name : removed) {
            element.removeAnnotation(name);
        }
        recordRemoved(removed);
        return self();
    }

    /**
     * Adds the removed names as a record on the element: a removal from some elements — a parameter — does
     * not reach every reader, while an addition does, so the readers are told what to treat as gone.
     */
    private void recordRemoved(java.util.List<String> removed) {
        for (String name : removed) {
            RemovedAnnotations.record(element, name);
        }
    }

    /**
     * Every annotation declared right here: some elements — a parameter, say — carry theirs in the declared
     * metadata rather than answering {@code getDeclaredAnnotationNames}.
     */
    private java.util.Set<String> declaredAnnotationNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(element.getDeclaredAnnotationNames());
        names.addAll(element.getAnnotationMetadata().getDeclaredMetadata().getAnnotationNames());
        return names;
    }
}
