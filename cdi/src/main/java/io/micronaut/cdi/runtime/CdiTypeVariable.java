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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * A type variable rebuilt from what the compiler recorded of one: its bounds, which is all the matching rules
 * of section 2.4.2.1 read of a variable.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class CdiTypeVariable implements TypeVariable<GenericDeclaration> {

    private final String name;
    private final Type[] bounds;

    CdiTypeVariable(String name, Type[] bounds) {
        this.name = name;
        this.bounds = bounds;
    }

    @Override
    public Type[] getBounds() {
        return bounds.clone();
    }

    @Override
    public GenericDeclaration getGenericDeclaration() {
        throw new UnsupportedOperationException("The declaration of a recorded variable is not kept");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public AnnotatedType[] getAnnotatedBounds() {
        return new AnnotatedType[0];
    }

    @Override
    public <T extends Annotation> @org.jspecify.annotations.Nullable T getAnnotation(Class<T> annotationClass) {
        return null;
    }

    @Override
    public Annotation[] getAnnotations() {
        return new Annotation[0];
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return new Annotation[0];
    }

    @Override
    public String toString() {
        return name;
    }
}
