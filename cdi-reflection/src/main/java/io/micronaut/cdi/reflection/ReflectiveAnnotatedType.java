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

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A class, described the way the specification's annotated model describes one — read off the class itself, as
 * far as an injection point's questions reach.
 *
 * @param <X> The type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ReflectiveAnnotatedType<X> implements AnnotatedType<X> {

    private final Class<X> type;
    /**
     * The annotations portable extensions added to the class so far, which read as the class's own.
     */
    private final List<Annotation> added;

    ReflectiveAnnotatedType(Class<X> type) {
        this(type, List.of());
    }

    /**
     * @param type  The class
     * @param added The annotations added to it so far, read as they are when asked: an observer of
     *              {@code ProcessAnnotatedType} sees what the observers before it added (section 11.5.6)
     */
    ReflectiveAnnotatedType(Class<X> type, List<Annotation> added) {
        this.type = type;
        this.added = added;
    }

    @Override
    public Class<X> getJavaClass() {
        return type;
    }

    @Override
    public Set<AnnotatedConstructor<X>> getConstructors() {
        throw new UnsupportedOperationException("The constructors of the annotated model cannot be read back "
            + "from the compiled class");
    }

    @Override
    public Set<AnnotatedMethod<? super X>> getMethods() {
        throw new UnsupportedOperationException("The methods of the annotated model cannot be read back from "
            + "the compiled class");
    }

    @Override
    public Set<AnnotatedField<? super X>> getFields() {
        throw new UnsupportedOperationException("The fields of the annotated model cannot be read back from "
            + "the compiled class");
    }

    @Override
    public Type getBaseType() {
        return type;
    }

    @Override
    public Set<Type> getTypeClosure() {
        return ReflectiveCdi.annotatedTypeClosure(type);
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        for (Annotation annotation : added) {
            if (annotation.annotationType() == annotationType && annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        return type.getAnnotation(annotationType);
    }

    @Override
    public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
        Set<T> annotations = new LinkedHashSet<>(List.of(type.getAnnotationsByType(annotationType)));
        for (Annotation annotation : added) {
            if (annotation.annotationType() == annotationType && annotationType.isInstance(annotation)) {
                annotations.add(annotationType.cast(annotation));
            }
        }
        return annotations;
    }

    @Override
    public Set<Annotation> getAnnotations() {
        Set<Annotation> annotations = new LinkedHashSet<>(List.of(type.getAnnotations()));
        annotations.addAll(added);
        return annotations;
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return getAnnotation(annotationType) != null;
    }

    @Override
    public String toString() {
        return "AnnotatedType[" + type.getName() + "]";
    }
}
