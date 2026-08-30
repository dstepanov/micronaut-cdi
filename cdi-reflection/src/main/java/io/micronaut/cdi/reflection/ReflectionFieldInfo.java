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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * A field, read back off the class.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionFieldInfo extends ReflectionAnnotations.Target implements FieldInfo {

    private final Field field;
    private final ClassInfo declaringClass;

    ReflectionFieldInfo(Field field, ClassInfo declaringClass) {
        this.field = field;
        this.declaringClass = declaringClass;
    }

    /**
     * The field this describes.
     *
     * @return The field
     */
    public Field field() {
        return field;
    }

    @Override
    AnnotatedElement annotated() {
        return field;
    }

    @Override
    public String name() {
        return field.getName();
    }

    @Override
    public Type type() {
        return ReflectionTypes.of(field.getGenericType());
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(field.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(field.getModifiers());
    }

    @Override
    public int modifiers() {
        return field.getModifiers();
    }

    @Override
    public ClassInfo declaringClass() {
        return declaringClass;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReflectionFieldInfo other && field.equals(other.field);
    }

    @Override
    public int hashCode() {
        return field.hashCode();
    }

    @Override
    public String toString() {
        return field.toString();
    }
}
