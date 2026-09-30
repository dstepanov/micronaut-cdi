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

import io.micronaut.cdi.annotation.CdiRecordedType;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Hands out a type that was recorded while the application compiled as the {@code java.lang.reflect.Type} the
 * specification's runtime interfaces are written in: the class is the one the compiled record refers to, and a
 * parameterized type, a wildcard and a type variable are composed from their recorded arguments and bounds.
 * Nothing is read back from a class.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RecordedTypes {

    private RecordedTypes() {
    }

    /**
     * The type a record describes.
     *
     * @param record The record, a {@link CdiRecordedType}
     * @return The type
     * @throws IllegalStateException Where a class the record refers to is not on the classpath
     */
    public static Type typeOf(AnnotationValue<?> record) {
        Type type = find(record);
        if (type == null) {
            throw new IllegalStateException("The recorded type " + record.stringValue().orElse("")
                + " is not on the classpath");
        }
        return type;
    }

    /**
     * The type a record describes, where every class it refers to is there.
     *
     * @param record The record, a {@link CdiRecordedType}
     * @return The type, or {@code null} where the record refers to a class that cannot be referred to from
     * where it was compiled, or is not on the classpath
     */
    public static @Nullable Type find(AnnotationValue<?> record) {
        return switch (record.stringValue("kind").orElse("CLASS")) {
            case "WILDCARD" -> {
                Type[] upper = all(record.getAnnotations(CdiRecordedType.BOUNDS));
                Type[] lower = all(record.getAnnotations(CdiRecordedType.LOWER_BOUNDS));
                yield upper == null || lower == null ? null
                    : new CdiWildcardType(upper.length == 0 ? new Type[] {Object.class} : upper, lower);
            }
            case "VARIABLE" -> {
                Type[] bounds = all(record.getAnnotations(CdiRecordedType.BOUNDS));
                yield bounds == null ? null : new CdiTypeVariable(record.stringValue("name").orElse("T"),
                    bounds.length == 0 ? new Type[] {Object.class} : bounds);
            }
            case "PRIMITIVE" -> arrayOf(primitive(record.stringValue("name").orElse("")), record);
            default -> classOf(record);
        };
    }

    private static @Nullable Type classOf(AnnotationValue<?> record) {
        Class<?> raw = record.classValue("value").orElse(null);
        if (raw == null) {
            return null;
        }
        List<AnnotationValue<Annotation>> arguments = record.getAnnotations(CdiRecordedType.ARGUMENTS);
        if (arguments.isEmpty()) {
            return arrayOf(raw, record);
        }
        Type[] typeArguments = all(arguments);
        return typeArguments == null ? null : CdiParameterizedType.of(raw, typeArguments);
    }

    private static Type @Nullable [] all(List<AnnotationValue<Annotation>> records) {
        Type[] types = new Type[records.size()];
        for (int i = 0; i < types.length; i++) {
            Type type = find(records.get(i));
            if (type == null) {
                return null;
            }
            types[i] = type;
        }
        return types;
    }

    private static @Nullable Type arrayOf(@Nullable Class<?> component, AnnotationValue<?> record) {
        if (component == null) {
            return null;
        }
        Class<?> type = component;
        for (int i = record.intValue("dimensions").orElse(0); i > 0; i--) {
            type = type.arrayType();
        }
        return type;
    }

    private static @Nullable Class<?> primitive(String name) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> null;
        };
    }
}
