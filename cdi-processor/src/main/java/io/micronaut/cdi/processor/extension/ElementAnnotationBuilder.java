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
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composes an annotation of the language model (section 2.10), as the values Micronaut records an annotation
 * with.
 *
 * <p>An extension composes an annotation to put it on a declaration, to qualify a synthetic bean with, or to
 * attach to one as a parameter, and in each case what becomes of it is annotation metadata of something the
 * compiler generates. So it is composed as that from the start: a member is kept in the form the metadata holds
 * it in - an enum constant by its name, a class by its name, a nested annotation as its values - and the
 * annotation interface is asked, through the compilation's view of it, only which members it declares and
 * what they default to. No annotation instance is made, and no class is loaded.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementAnnotationBuilder implements AnnotationBuilder {

    private final String annotation;
    private final Map<CharSequence, Object> members = new LinkedHashMap<>();

    ElementAnnotationBuilder(String annotation) {
        this.annotation = annotation;
    }

    @Override
    public AnnotationInfo build() {
        Map<CharSequence, Object> values = new LinkedHashMap<>(members);
        VisitorContext context = BuildCompatibleExtensionVisitor.activeVisitorContext();
        if (context != null) {
            // a member that was not given is the default its interface declares, and one without a default
            // has to be given (section 2.10)
            context.getAnnotationDefaultValues(annotation).forEach(values::putIfAbsent);
            ClassElement declaration = context.getClassElement(annotation).orElse(null);
            if (declaration != null) {
                for (MethodElement member
                    : declaration.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
                    if (!values.containsKey(member.getName())) {
                        throw new IllegalStateException("The member " + member.getName() + " of " + annotation
                            + " was not composed and has no default");
                    }
                }
            }
        }
        return new ElementAnnotationInfo(new AnnotationValue<>(annotation, values));
    }

    private AnnotationBuilder put(String name, Object value) {
        members.put(name, value);
        return this;
    }

    private static AnnotationValue<?> valueOf(AnnotationInfo info) {
        if (info instanceof ElementAnnotationInfo element) {
            return element.annotationValue();
        }
        throw new IllegalArgumentException("The annotation " + info.name() + " was not composed by the language "
            + "model of this compilation");
    }

    private static AnnotationClassValue<?> classOf(Type type) {
        if (type.isClass()) {
            return new AnnotationClassValue<>(type.asClass().declaration().name());
        }
        if (type.isParameterizedType()) {
            return new AnnotationClassValue<>(type.asParameterizedType().genericClass().declaration().name());
        }
        if (type.isPrimitive() || type.isVoid()) {
            return new AnnotationClassValue<>(ElementTypes.elementOf(type).getName());
        }
        throw new IllegalArgumentException("The type " + type + " is not one a class member of an annotation "
            + "names");
    }

    private static String[] namesOf(Enum<?>[] constants) {
        String[] names = new String[constants.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = constants[i].name();
        }
        return names;
    }

    @Override
    public AnnotationBuilder member(String name, boolean value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, boolean[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, byte value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, byte[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, short value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, short[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, int value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, int[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, long value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, long[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, float value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, float[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, double value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, double[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, char value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, char[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, String value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, String[] value) {
        return put(name, value.clone());
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?> value) {
        return put(name, value.name());
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?>[] value) {
        return put(name, namesOf(value));
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String enumValue) {
        return put(name, enumValue);
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String[] enumValues) {
        return put(name, enumValues.clone());
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String enumValue) {
        return put(name, enumValue);
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String[] enumValues) {
        return put(name, enumValues.clone());
    }

    @Override
    public AnnotationBuilder member(String name, Class<?> value) {
        return put(name, new AnnotationClassValue<>(value.getName()));
    }

    @Override
    public AnnotationBuilder member(String name, Class<?>[] values) {
        AnnotationClassValue<?>[] classes = new AnnotationClassValue<?>[values.length];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = new AnnotationClassValue<>(values[i].getName());
        }
        return put(name, classes);
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo value) {
        return put(name, new AnnotationClassValue<>(value.name()));
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo[] values) {
        AnnotationClassValue<?>[] classes = new AnnotationClassValue<?>[values.length];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = new AnnotationClassValue<>(values[i].name());
        }
        return put(name, classes);
    }

    @Override
    public AnnotationBuilder member(String name, Type value) {
        return put(name, classOf(value));
    }

    @Override
    public AnnotationBuilder member(String name, Type[] values) {
        AnnotationClassValue<?>[] classes = new AnnotationClassValue<?>[values.length];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = classOf(values[i]);
        }
        return put(name, classes);
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo value) {
        return put(name, valueOf(value));
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo[] values) {
        AnnotationValue<?>[] annotations = new AnnotationValue<?>[values.length];
        for (int i = 0; i < annotations.length; i++) {
            annotations[i] = valueOf(values[i]);
        }
        return put(name, annotations);
    }

    @Override
    public AnnotationBuilder member(String name, Annotation value) {
        return put(name, AnnotationValue.of(value));
    }

    @Override
    public AnnotationBuilder member(String name, Annotation[] values) {
        AnnotationValue<?>[] annotations = new AnnotationValue<?>[values.length];
        for (int i = 0; i < annotations.length; i++) {
            annotations[i] = AnnotationValue.of(values[i]);
        }
        return put(name, annotations);
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationMember value) {
        if (value instanceof ElementAnnotationMember member) {
            return put(name, member.recordedValue());
        }
        throw new IllegalArgumentException("The annotation member was not read from the language model of this "
            + "compilation");
    }
}
