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

import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads a type that was recorded while the application compiled as the {@link Argument} the container works
 * with: the class is the one the compiled record refers to, and a parameterized type, a wildcard and a type
 * variable are composed from their recorded arguments and bounds. Nothing is read back from a class.
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
    public static Argument<?> typeOf(AnnotationValue<?> record) {
        Argument<?> type = find(record);
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
    public static @Nullable Argument<?> find(AnnotationValue<?> record) {
        return switch (record.stringValue("kind").orElse("CLASS")) {
            case "WILDCARD" -> {
                Argument<?>[] upper = all(record.getAnnotations(CdiRecordedType.BOUNDS));
                Argument<?>[] lower = all(record.getAnnotations(CdiRecordedType.LOWER_BOUNDS));
                if (upper == null || lower == null) {
                    yield null;
                }
                Argument<?> first = upper.length == 0 ? Argument.OBJECT_ARGUMENT : upper[0];
                yield Argument.ofWildcard(first.getType(), null, null, first.getTypeParameters(), upper, lower);
            }
            case "VARIABLE" -> {
                Argument<?>[] bounds = all(record.getAnnotations(CdiRecordedType.BOUNDS));
                yield bounds == null ? null : CdiTypes.variable(record.stringValue("name").orElse("T"), bounds);
            }
            case "PRIMITIVE" -> arrayOf(primitive(record.stringValue("name").orElse("")), record);
            default -> classOf(record);
        };
    }

    private static @Nullable Argument<?> classOf(AnnotationValue<?> record) {
        Class<?> raw = record.classValue("value").orElse(null);
        if (raw == null) {
            return null;
        }
        List<AnnotationValue<Annotation>> arguments = record.getAnnotations(CdiRecordedType.ARGUMENTS);
        if (arguments.isEmpty()) {
            return arrayOf(raw, record);
        }
        Argument<?>[] typeArguments = all(arguments);
        if (typeArguments == null) {
            return null;
        }
        // an array of a parameterized type, List<String>[], keeps the arguments of its element type
        Argument<?> type = Argument.of(raw, (String) null, typeArguments);
        for (int i = record.intValue("dimensions").orElse(0); i > 0; i--) {
            type = type.arrayType();
        }
        return type;
    }

    private static Argument<?> @Nullable [] all(List<AnnotationValue<Annotation>> records) {
        Argument<?>[] types = new Argument<?>[records.size()];
        for (int i = 0; i < types.length; i++) {
            Argument<?> type = find(records.get(i));
            if (type == null) {
                return null;
            }
            types[i] = type;
        }
        return types;
    }

    private static @Nullable Argument<?> arrayOf(@Nullable Class<?> component, AnnotationValue<?> record) {
        if (component == null) {
            return null;
        }
        Class<?> type = component;
        for (int i = record.intValue("dimensions").orElse(0); i > 0; i--) {
            type = type.arrayType();
        }
        return Argument.of(type);
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
