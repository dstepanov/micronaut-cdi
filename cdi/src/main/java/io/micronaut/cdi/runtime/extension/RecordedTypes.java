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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.cdi.annotation.CdiRecordedType;
import io.micronaut.cdi.runtime.CdiParameterizedType;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Hands out a type an extension composed while the application compiled as the {@code java.lang.reflect.Type}
 * the specification's runtime interfaces are written in, from what was recorded of it: the class is the one the
 * compiled record refers to, and a parameterized type is composed from its recorded arguments.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class RecordedTypes {

    private RecordedTypes() {
    }

    /**
     * The type a record describes.
     *
     * @param record The record, a {@link CdiRecordedType}
     * @return The type
     */
    static Type typeOf(AnnotationValue<?> record) {
        Class<?> raw = record.classValue("value").orElseThrow(() ->
            new IllegalStateException("The recorded type " + record.stringValue().orElse("")
                + " is not on the classpath"));
        List<AnnotationValue<Annotation>> arguments = record.getAnnotations(CdiRecordedType.ARGUMENTS);
        Type type = raw;
        if (!arguments.isEmpty()) {
            Type[] typeArguments = new Type[arguments.size()];
            for (int i = 0; i < typeArguments.length; i++) {
                typeArguments[i] = typeOf(arguments.get(i));
            }
            type = CdiParameterizedType.of(raw, typeArguments);
        }
        int dimensions = record.intValue("dimensions").orElse(0);
        if (dimensions > 0 && type instanceof Class<?> component) {
            for (int i = 0; i < dimensions; i++) {
                component = component.arrayType();
            }
            return component;
        }
        return type;
    }
}
