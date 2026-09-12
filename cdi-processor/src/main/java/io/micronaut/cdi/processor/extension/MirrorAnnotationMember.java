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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.List;

/**
 * One member of an annotation, read from the value the compiler recorded for it.
 *
 * <p>The compiler records the value of a member as the thing the source named: a boxed primitive, a string, the
 * type a class literal named, the enum constant an enum member named, a nested use of an annotation, or a list
 * of any of those. Each is what the language model asks about, so each is handed on as it is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class MirrorAnnotationMember implements AnnotationMember {

    private final AnnotationValue value;
    private final @Nullable Elements elements;

    MirrorAnnotationMember(AnnotationValue value, @Nullable Elements elements) {
        this.value = value;
        this.elements = elements;
    }

    @Override
    public Kind kind() {
        Object recorded = value.getValue();
        if (recorded instanceof Boolean) {
            return Kind.BOOLEAN;
        }
        if (recorded instanceof Byte) {
            return Kind.BYTE;
        }
        if (recorded instanceof Short) {
            return Kind.SHORT;
        }
        if (recorded instanceof Integer) {
            return Kind.INT;
        }
        if (recorded instanceof Long) {
            return Kind.LONG;
        }
        if (recorded instanceof Float) {
            return Kind.FLOAT;
        }
        if (recorded instanceof Double) {
            return Kind.DOUBLE;
        }
        if (recorded instanceof Character) {
            return Kind.CHAR;
        }
        if (recorded instanceof TypeMirror) {
            return Kind.CLASS;
        }
        if (recorded instanceof VariableElement) {
            return Kind.ENUM;
        }
        if (recorded instanceof AnnotationMirror) {
            return Kind.NESTED_ANNOTATION;
        }
        if (recorded instanceof List<?>) {
            return Kind.ARRAY;
        }
        return Kind.STRING;
    }

    @Override
    public boolean asBoolean() {
        return (Boolean) value.getValue();
    }

    @Override
    public byte asByte() {
        return ((Number) value.getValue()).byteValue();
    }

    @Override
    public short asShort() {
        return ((Number) value.getValue()).shortValue();
    }

    @Override
    public int asInt() {
        return ((Number) value.getValue()).intValue();
    }

    @Override
    public long asLong() {
        return ((Number) value.getValue()).longValue();
    }

    @Override
    public float asFloat() {
        return ((Number) value.getValue()).floatValue();
    }

    @Override
    public double asDouble() {
        return ((Number) value.getValue()).doubleValue();
    }

    @Override
    public char asChar() {
        return (Character) value.getValue();
    }

    @Override
    public String asString() {
        return String.valueOf(value.getValue());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E extends Enum<E>> E asEnum(Class<E> enumType) {
        return Enum.valueOf(enumType, asEnumConstant());
    }

    @Override
    public ClassInfo asEnumClass() {
        VariableElement constant = (VariableElement) value.getValue();
        if (!(constant.getEnclosingElement() instanceof TypeElement enumType)) {
            throw new IllegalStateException("The enum constant " + constant + " belongs to no enum");
        }
        String name = ExtensionSourceModel.nameOf(enumType);
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(name);
        if (declaration == null) {
            throw new IllegalStateException("The enum " + name + " is not on the compilation's classpath");
        }
        return new ElementClassInfo(declaration);
    }

    @Override
    public String asEnumConstant() {
        return ((VariableElement) value.getValue()).getSimpleName().toString();
    }

    @Override
    public Type asType() {
        return ExtensionSourceModel.typeOf((TypeMirror) value.getValue());
    }

    @Override
    public AnnotationInfo asNestedAnnotation() {
        return new MirrorAnnotationInfo((AnnotationMirror) value.getValue(), elements);
    }

    @Override
    public List<AnnotationMember> asArray() {
        List<AnnotationMember> members = new ArrayList<>();
        for (Object element : (List<?>) value.getValue()) {
            members.add(new MirrorAnnotationMember((AnnotationValue) element, elements));
        }
        return members;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
