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
import jakarta.enterprise.inject.spi.AnnotatedCallable;
import jakarta.enterprise.inject.spi.AnnotatedParameter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A parameter of an injection point's method or constructor, described the way the specification's annotated
 * model describes one — read off the executable itself.
 *
 * @param <X> The declaring type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ReflectiveAnnotatedParameter<X> implements AnnotatedParameter<X> {

    private final Executable executable;
    private final int position;

    ReflectiveAnnotatedParameter(Executable executable, int position) {
        this.executable = executable;
        this.position = position;
    }

    private Parameter parameter() {
        return executable.getParameters()[position];
    }

    @Override
    public int getPosition() {
        return position;
    }

    @Override
    public AnnotatedCallable<X> getDeclaringCallable() {
        throw new UnsupportedOperationException("The annotated model of the declaring callable cannot be read "
            + "back from the compiled class");
    }

    @Override
    public Type getBaseType() {
        return parameter().getParameterizedType();
    }

    @Override
    public Set<Type> getTypeClosure() {
        return Set.of(parameter().getParameterizedType(), Object.class);
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        return parameter().getAnnotation(annotationType);
    }

    @Override
    public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
        return Set.of(parameter().getAnnotationsByType(annotationType));
    }

    @Override
    public Set<Annotation> getAnnotations() {
        return new LinkedHashSet<>(java.util.List.of(parameter().getAnnotations()));
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return parameter().isAnnotationPresent(annotationType);
    }

    @Override
    public String toString() {
        return "AnnotatedParameter[" + executable + "#" + position + "]";
    }
}
