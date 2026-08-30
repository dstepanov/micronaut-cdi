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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * One member of an annotation, read from the value Micronaut recorded for it.
 *
 * <p>Micronaut records the value of an annotation member as whatever object it was written as — a boxed
 * primitive, a string, an {@link AnnotationClassValue} for a class, a nested {@link AnnotationValue}, an array of
 * any of those. The language model asks instead what kind of member it is and then for the value of that kind,
 * so what is recorded is read back into those terms.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementAnnotationMember implements AnnotationMember {

    private final Object value;

    ElementAnnotationMember(Object value) {
        this.value = value;
    }

    @Override
    public Kind kind() {
        if (value instanceof Boolean) {
            return Kind.BOOLEAN;
        }
        if (value instanceof Byte) {
            return Kind.BYTE;
        }
        if (value instanceof Short) {
            return Kind.SHORT;
        }
        if (value instanceof Integer) {
            return Kind.INT;
        }
        if (value instanceof Long) {
            return Kind.LONG;
        }
        if (value instanceof Float) {
            return Kind.FLOAT;
        }
        if (value instanceof Double) {
            return Kind.DOUBLE;
        }
        if (value instanceof Character) {
            return Kind.CHAR;
        }
        if (value instanceof Enum<?>) {
            return Kind.ENUM;
        }
        if (value instanceof AnnotationClassValue<?>) {
            return Kind.CLASS;
        }
        if (value instanceof AnnotationValue<?>) {
            return Kind.NESTED_ANNOTATION;
        }
        if (value.getClass().isArray() || value instanceof Iterable<?>) {
            return Kind.ARRAY;
        }
        return Kind.STRING;
    }

    @Override
    public boolean asBoolean() {
        return (Boolean) value;
    }

    @Override
    public byte asByte() {
        return ((Number) value).byteValue();
    }

    @Override
    public short asShort() {
        return ((Number) value).shortValue();
    }

    @Override
    public int asInt() {
        return ((Number) value).intValue();
    }

    @Override
    public long asLong() {
        return ((Number) value).longValue();
    }

    @Override
    public float asFloat() {
        return ((Number) value).floatValue();
    }

    @Override
    public double asDouble() {
        return ((Number) value).doubleValue();
    }

    @Override
    public char asChar() {
        return (Character) value;
    }

    @Override
    public String asString() {
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E extends Enum<E>> E asEnum(Class<E> enumType) {
        if (enumType.isInstance(value)) {
            return (E) value;
        }
        return Enum.valueOf(enumType, asEnumConstant());
    }

    @Override
    public ClassInfo asEnumClass() {
        throw new IllegalStateException("The class an enum constant belongs to is not recorded with the constant");
    }

    @Override
    public String asEnumConstant() {
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }

    @Override
    public Type asType() {
        throw new IllegalStateException("A class named by an annotation member is recorded by name, and the type "
            + "it names is not resolved here");
    }

    @Override
    public AnnotationInfo asNestedAnnotation() {
        return new ElementAnnotationInfo((AnnotationValue<?>) value);
    }

    @Override
    public List<AnnotationMember> asArray() {
        List<AnnotationMember> members = new ArrayList<>();
        if (value instanceof Iterable<?> values) {
            values.forEach(element -> members.add(new ElementAnnotationMember(element)));
            return members;
        }
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            members.add(new ElementAnnotationMember(Array.get(value, i)));
        }
        return members;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
