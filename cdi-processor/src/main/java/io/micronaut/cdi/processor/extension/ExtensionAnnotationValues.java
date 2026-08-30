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
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Reads a live annotation instance — a literal an extension composed — into the value the compiler's metadata
 * carries, members included.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionAnnotationValues {

    private ExtensionAnnotationValues() {
    }

    /**
     * The annotation as a value of the compiler's metadata, with every member the instance carries.
     *
     * @param annotation The annotation instance
     * @return The value
     */
    static AnnotationValue<?> of(Annotation annotation) {
        AnnotationValueBuilder<?> builder = AnnotationValue.builder(annotation.annotationType().getName());
        for (Method member : annotation.annotationType().getDeclaredMethods()) {
            Object value;
            try {
                member.setAccessible(true);
                value = member.invoke(annotation);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("The member " + member.getName() + " of " + annotation
                    + " could not be read", e);
            }
            builder = with(builder, member.getName(), value);
        }
        return builder.build();
    }

    private static AnnotationValueBuilder<?> with(AnnotationValueBuilder<?> builder, String name, Object value) {
        if (value instanceof Annotation nested) {
            return builder.member(name, of(nested));
        }
        if (value instanceof Class<?> aClass) {
            return builder.member(name, new AnnotationClassValue<>(aClass.getName()));
        }
        if (value instanceof Enum<?> anEnum) {
            return builder.member(name, anEnum);
        }
        if (value instanceof String string) {
            return builder.member(name, string);
        }
        if (value instanceof Boolean b) {
            return builder.member(name, b);
        }
        if (value instanceof Integer i) {
            return builder.member(name, i);
        }
        if (value instanceof Long l) {
            return builder.member(name, l);
        }
        if (value instanceof Double d) {
            return builder.member(name, d);
        }
        if (value instanceof Float fl) {
            return builder.member(name, fl);
        }
        if (value instanceof Short sh) {
            return builder.member(name, sh);
        }
        if (value instanceof Byte by) {
            return builder.member(name, by);
        }
        if (value instanceof Character ch) {
            return builder.member(name, String.valueOf(ch));
        }
        if (value instanceof String[] strings) {
            return builder.member(name, strings);
        }
        if (value instanceof Class<?>[] classes) {
            AnnotationClassValue<?>[] values = new AnnotationClassValue<?>[classes.length];
            for (int i = 0; i < classes.length; i++) {
                values[i] = new AnnotationClassValue<>(classes[i].getName());
            }
            return builder.member(name, values);
        }
        if (value instanceof Annotation[] annotations) {
            AnnotationValue<?>[] values = new AnnotationValue<?>[annotations.length];
            for (int i = 0; i < annotations.length; i++) {
                values[i] = of(annotations[i]);
            }
            return builder.member(name, values);
        }
        if (value instanceof int[] ints) {
            return builder.member(name, ints);
        }
        // an exotic member — a primitive array of another kind — is left off rather than failing the build
        return builder;
    }
}
