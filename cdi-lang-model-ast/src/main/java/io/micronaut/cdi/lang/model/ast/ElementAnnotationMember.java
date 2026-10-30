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
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.AnnotationMemberElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
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
    // null for a member of an annotation an extension composed with no compilation under way, which only
    // answers what it was given
    private final @Nullable VisitorContext context;

    ElementAnnotationMember(Object value, VisitorContext context) {
        this(value, null, null, context);
    }

    ElementAnnotationMember(Object value, @Nullable String annotation, @Nullable String member,
                            @Nullable VisitorContext context) {
        this.value = value;
        this.annotation = annotation;
        this.member = member;
        this.context = context;
    }

    /**
     * The value as Micronaut records it, which is what composing another annotation from it takes.
     *
     * @return The recorded value
     */
    public Object recordedValue() {
        return value;
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
            return ElementTypes.ofName(constant.getDeclaringClass().getName(), compilation()).asClass().declaration();
        }
        ClassElement declared = memberType();
        if (declared == null) {
            throw new IllegalStateException("The enum the member " + member + " of " + annotation
                + " belongs to cannot be resolved in this compilation");
        }
        return ElementClassInfo.declarationOf(declared, compilation());
    }

    @Override
    public String asEnumConstant() {
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }

    @Override
    public Type asType() {
        if (value instanceof AnnotationClassValue<?> classValue) {
            return ElementTypes.ofName(classValue.getName(), compilation());
        }
        return ElementTypes.ofName(value.toString(), compilation());
    }

    @Override
    public AnnotationInfo asNestedAnnotation() {
        AnnotationValue<?> nested = (AnnotationValue<?>) value;
        return context == null ? ElementAnnotationInfo.composedWithoutCompilation(nested)
            : new ElementAnnotationInfo(nested, context);
    }

    @Override
    public List<AnnotationMember> asArray() {
        List<AnnotationMember> members = new ArrayList<>();
        if (value instanceof Iterable<?> values) {
            values.forEach(element ->
                members.add(new ElementAnnotationMember(element, annotation, member, context)));
            return List.copyOf(members);
        }
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            members.add(new ElementAnnotationMember(Array.get(value, i), annotation, member, context));
        }
        return List.copyOf(members);
    }

    /**
     * The type the member's declaration in the annotation interface returns, a component of it for an array
     * member, or {@code null} where the declaration cannot be resolved.
     */
    private @Nullable ClassElement memberType() {
        if (annotation == null || member == null || context == null) {
            return null;
        }
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(annotation, context);
        if (!(declaration instanceof AnnotationElement annotationElement)) {
            return null;
        }
        for (AnnotationMemberElement declared : annotationElement.getMembers()) {
            if (declared.getName().equals(member)) {
                ClassElement type = declared.getType();
                return type.isArray() ? type.fromArray() : type;
            }
        }
        return null;
    }

    /**
     * The compilation the classes a member names are resolved in, which an annotation composed with none
     * cannot name a class through.
     */
    private VisitorContext compilation() {
        if (context == null) {
            throw new IllegalStateException("The member " + member + " of " + annotation
                + " was composed with no compilation under way to resolve what it names in");
        }
        return context;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
