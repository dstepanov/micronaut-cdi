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

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The field of an injection point, described the way the specification's annotated model describes one — read
 * off the field itself, which carries everything the model asks for.
 *
 * @param <X> The declaring type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ReflectiveAnnotatedField<X> implements AnnotatedField<X> {

    private final Field field;

    ReflectiveAnnotatedField(Field field) {
        this.field = field;
    }

    @Override
    public Field getJavaMember() {
        return field;
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(field.getModifiers());
    }

    @SuppressWarnings("unchecked")
    @Override
    public AnnotatedType<X> getDeclaringType() {
        return new ReflectiveAnnotatedType<>((Class<X>) field.getDeclaringClass());
    }

    @Override
    public Type getBaseType() {
        return field.getGenericType();
    }

    @Override
    public Set<Type> getTypeClosure() {
        return Set.of(field.getGenericType(), Object.class);
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        return field.getAnnotation(annotationType);
    }

    @Override
    public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
        return Set.of(field.getAnnotationsByType(annotationType));
    }

    @Override
    public Set<Annotation> getAnnotations() {
        return new LinkedHashSet<>(java.util.List.of(field.getAnnotations()));
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return field.isAnnotationPresent(annotationType);
    }

    @Override
    public String toString() {
        return "AnnotatedField[" + field + "]";
    }
}
