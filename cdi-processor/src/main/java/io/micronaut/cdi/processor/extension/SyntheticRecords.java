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

import io.micronaut.cdi.lang.model.ast.ElementAnnotationInfo;
import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.cdi.processor.visitor.RecordedTypeValues;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns what an extension hands a synthetic component builder into the annotation values the component is
 * recorded with.
 *
 * <p>The synthesis phase runs inside the compiler, and what it describes reaches the running application as
 * the annotation metadata of a generated bean definition. Everything the specification lets an extension attach
 * — a parameter of any of the allowed types, a qualifier, an event type — is something an annotation value
 * holds, and this is where each is written as one.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SyntheticRecords {

    private SyntheticRecords() {
    }

    /**
     * The class of the given name as the compilation sees it.
     *
     * @param context The compilation
     * @param type    A class the extension named
     * @param role    What the class is named as, for the message when the compilation cannot see it
     * @return The class
     */
    static ClassElement classElement(VisitorContext context, Class<?> type, String role) {
        return classElement(context, type.getName(), role);
    }

    /**
     * The class of the given name as the compilation sees it.
     *
     * @param context The compilation
     * @param name    The binary name of a class the extension named
     * @param role    What the class is named as, for the message when the compilation cannot see it
     * @return The class
     */
    static ClassElement classElement(VisitorContext context, String name, String role) {
        return context.getClassElement(name)
            .or(() -> context.getClassElement(name.replace('$', '.')))
            .orElseThrow(() -> new IllegalArgumentException("The " + role + " " + name + " is not on the "
                + "classpath of the compilation: a class a build compatible extension names for a synthetic "
                + "component has to be compiled with, or visible to, the application the extension runs for"));
    }

    /**
     * The values of an annotation given by its type alone, every member at its default.
     *
     * @param context The compilation
     * @param type    The annotation type
     * @return The values
     */
    static AnnotationValue<?> annotationOf(VisitorContext context, Class<? extends Annotation> type) {
        return complete(context, AnnotationValue.builder(type.getName()).build());
    }

    /**
     * The values of an annotation given as an instance.
     *
     * @param annotation The annotation
     * @return The values, every member among them
     */
    static AnnotationValue<?> annotationOf(Annotation annotation) {
        return AnnotationValue.of(annotation);
    }

    /**
     * The values of an annotation given in the language model.
     *
     * @param context    The compilation
     * @param annotation The annotation
     * @return The values, every member among them
     */
    static AnnotationValue<?> annotationOf(VisitorContext context, AnnotationInfo annotation) {
        if (annotation instanceof ElementAnnotationInfo info) {
            return complete(context, info.annotationValue());
        }
        throw new IllegalArgumentException("The annotation " + annotation.name() + " was not composed by the "
            + "language model of this compilation");
    }

    /**
     * The annotation with every member it did not write filled in from the defaults its interface declares,
     * so that what is recorded can be read without the interface.
     */
    private static AnnotationValue<?> complete(VisitorContext context, AnnotationValue<?> annotation) {
        Map<CharSequence, Object> values = new LinkedHashMap<>(annotation.getValues());
        context.getAnnotationDefaultValues(annotation.getAnnotationName()).forEach(values::putIfAbsent);
        return new AnnotationValue<>(annotation.getAnnotationName(), values);
    }

    /**
     * A type of the language model as the record the container composes a {@code java.lang.reflect.Type} from:
     * the record of a compiled type, a wildcard and a type variable among its arguments included.
     *
     * @param type The type
     * @return The record
     */
    static AnnotationValue<CdiRecordedType> typeOf(Type type) {
        return RecordedTypeValues.of(
            ElementTypes.elementOf(type));
    }

    /**
     * A bean type an extension declared as the record the container reads it back from: a class by itself is
     * recorded as the class, whatever type parameters it declares, and a parameterized type with its arguments.
     *
     * @param type The type
     * @return The record
     */
    static AnnotationValue<CdiRecordedType> beanTypeOf(Type type) {
        if (type.isClass()) {
            return AnnotationValue.builder(CdiRecordedType.class)
                .member(AnnotationMetadata.VALUE_MEMBER,
                    new AnnotationClassValue<>(ElementTypes.elementOf(type).getName()))
                .build();
        }
        return RecordedTypeValues.ofBeanType(ElementTypes.elementOf(type));
    }

    private static AnnotationValueBuilder<CdiSyntheticParameter> parameter(String key, String kind, boolean array) {
        AnnotationValueBuilder<CdiSyntheticParameter> parameter = AnnotationValue.builder(CdiSyntheticParameter.class)
            .member("name", key)
            .member("kind", kind);
        if (array) {
            parameter.member("array", true);
        }
        return parameter;
    }

    static AnnotationValue<CdiSyntheticParameter> booleans(String key, boolean array, boolean... values) {
        return parameter(key, "BOOLEAN", array).member("booleans", values).build();
    }

    static AnnotationValue<CdiSyntheticParameter> ints(String key, boolean array, int... values) {
        return parameter(key, "INT", array).member("ints", values).build();
    }

    static AnnotationValue<CdiSyntheticParameter> longs(String key, boolean array, long... values) {
        return parameter(key, "LONG", array).member("longs", values).build();
    }

    static AnnotationValue<CdiSyntheticParameter> doubles(String key, boolean array, double... values) {
        return parameter(key, "DOUBLE", array).member("doubles", values).build();
    }

    static AnnotationValue<CdiSyntheticParameter> strings(String key, boolean array, String... values) {
        return parameter(key, "STRING", array).member("strings", values).build();
    }

    static AnnotationValue<CdiSyntheticParameter> enums(String key, boolean array, Class<?> enumType,
                                                        Enum<?>... values) {
        String[] names = new String[values.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = values[i].name();
        }
        return parameter(key, "ENUM", array)
            .member("strings", names)
            .member("type", new AnnotationClassValue<>(enumType.getName()))
            .build();
    }

    static AnnotationValue<CdiSyntheticParameter> classes(String key, boolean array, String... names) {
        AnnotationClassValue<?>[] classes = new AnnotationClassValue<?>[names.length];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = new AnnotationClassValue<>(names[i]);
        }
        return parameter(key, "CLASS", array).member("classes", classes).build();
    }

    static String[] namesOf(Class<?>... classes) {
        String[] names = new String[classes.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = classes[i].getName();
        }
        return names;
    }

    static String[] namesOf(ClassInfo... classes) {
        String[] names = new String[classes.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = classes[i].name();
        }
        return names;
    }

    static AnnotationValue<CdiSyntheticParameter> annotations(String key, boolean array,
                                                              AnnotationValue<?>... values) {
        AnnotationClassValue<?>[] types = new AnnotationClassValue<?>[values.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = new AnnotationClassValue<>(values[i].getAnnotationName());
        }
        return parameter(key, "ANNOTATION", array)
            .member(CdiSyntheticParameter.ANNOTATIONS, values)
            .member("classes", types)
            .build();
    }

    static AnnotationValue<?>[] valuesOf(Annotation... annotations) {
        AnnotationValue<?>[] values = new AnnotationValue<?>[annotations.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = annotationOf(annotations[i]);
        }
        return values;
    }

    static AnnotationValue<?>[] valuesOf(VisitorContext context, AnnotationInfo... annotations) {
        AnnotationValue<?>[] values = new AnnotationValue<?>[annotations.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = annotationOf(context, annotations[i]);
        }
        return values;
    }

    static AnnotationValue<CdiSyntheticParameter> invokers(String key, boolean array, InvokerInfo... values) {
        AnnotationValue<?>[] records = new AnnotationValue<?>[values.length];
        for (int i = 0; i < records.length; i++) {
            if (!(values[i] instanceof ElementInvokerInfo invoker)) {
                throw new IllegalArgumentException("The invoker " + values[i] + " was not built by the "
                    + "registration phase of this compilation");
            }
            records[i] = invoker.toRecord();
        }
        return parameter(key, "INVOKER", array).member(CdiSyntheticParameter.INVOKERS, records).build();
    }
}
