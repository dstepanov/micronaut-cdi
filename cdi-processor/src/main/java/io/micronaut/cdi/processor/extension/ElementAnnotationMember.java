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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * A member of an annotation, read from the value Micronaut recorded for it.
 *
 * <p>Micronaut records an enum constant by its name and a class by its name, so what the value is of is read
 * off the member's declaration in the annotation interface: the type the member returns says whether a string
 * is an enum constant, and which enum it belongs to.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementAnnotationMember implements AnnotationMember {

    private final Object value;
    private final @Nullable String annotation;
    private final @Nullable String member;

    ElementAnnotationMember(Object value) {
        this(value, null, null);
    }

    ElementAnnotationMember(Object value, @Nullable String annotation, @Nullable String member) {
        this.value = value;
        this.annotation = annotation;
        this.member = member;
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
        // a constant of an enum is recorded by its name
        ClassElement declared = memberType();
        return declared != null && declared.isEnum() ? Kind.ENUM : Kind.STRING;
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
        return value instanceof Character character ? character : value.toString().charAt(0);
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
        if (value instanceof Enum<?> constant) {
            return ElementTypes.ofName(constant.getDeclaringClass().getName()).asClass().declaration();
        }
        ClassElement declared = memberType();
        if (declared == null) {
            throw new IllegalStateException("The enum the member " + member + " of " + annotation
                + " belongs to cannot be resolved in this compilation");
        }
        return ElementClassInfo.declarationOf(declared);
    }

    @Override
    public String asEnumConstant() {
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }

    @Override
    public Type asType() {
        if (value instanceof AnnotationClassValue<?> classValue) {
            return ElementTypes.ofName(classValue.getName());
        }
        return ElementTypes.ofName(value.toString());
    }

    @Override
    public AnnotationInfo asNestedAnnotation() {
        return new ElementAnnotationInfo((AnnotationValue<?>) value);
    }

    @Override
    public List<AnnotationMember> asArray() {
        List<AnnotationMember> members = new ArrayList<>();
        if (value instanceof Iterable<?> values) {
            values.forEach(element -> members.add(new ElementAnnotationMember(element, annotation, member)));
            return members;
        }
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            members.add(new ElementAnnotationMember(Array.get(value, i), annotation, member));
        }
        return members;
    }

    /**
     * The type the member's declaration in the annotation interface returns, a component of it for an array
     * member, or {@code null} where the declaration cannot be resolved.
     */
    private @Nullable ClassElement memberType() {
        if (annotation == null || member == null) {
            return null;
        }
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(annotation);
        if (declaration == null) {
            return null;
        }
        MethodElement declared = declaration
            .getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared().named(member)).stream()
            .findFirst().orElse(null);
        if (declared == null) {
            return null;
        }
        ClassElement type = declared.getReturnType();
        return type.isArray() ? type.fromArray() : type;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
