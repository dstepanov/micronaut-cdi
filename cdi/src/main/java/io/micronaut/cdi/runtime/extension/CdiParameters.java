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

import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.cdi.runtime.CdiAnnotations;
import io.micronaut.cdi.runtime.RecordedInvoker;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameters an extension attached to a synthetic bean or observer, handed back to whatever creates,
 * disposes of or observes.
 *
 * <p>They are how an extension carries what it knew when it described the component over to the moment the
 * component is used. The extension described it while the application compiled, so what it attached was
 * recorded as annotation values on the definition generated for the component; this reads a value back in the
 * type it is asked for. Nothing is read reflectively but an annotation parameter asked for as an instance of
 * its annotation type, which is what an annotation instance is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiParameters implements Parameters {

    private final Map<String, AnnotationValue<CdiSyntheticParameter>> parameters;

    CdiParameters(List<AnnotationValue<CdiSyntheticParameter>> recorded) {
        Map<String, AnnotationValue<CdiSyntheticParameter>> byName = new LinkedHashMap<>();
        for (AnnotationValue<CdiSyntheticParameter> parameter : recorded) {
            byName.put(parameter.stringValue("name").orElseThrow(), parameter);
        }
        this.parameters = byName;
    }

    /**
     * The invokers among the parameters, which the container checks the lookups of as it starts.
     *
     * @return The invokers
     */
    List<RecordedInvoker> invokers() {
        List<RecordedInvoker> invokers = new java.util.ArrayList<>();
        for (AnnotationValue<CdiSyntheticParameter> parameter : parameters.values()) {
            for (AnnotationValue<Annotation> invoker : parameter.getAnnotations("invokers")) {
                invokers.add(RecordedInvoker.of(invoker));
            }
        }
        return invokers;
    }

    @Override
    public <T> @Nullable T get(String key, Class<T> type) {
        AnnotationValue<CdiSyntheticParameter> parameter = parameters.get(key);
        if (parameter == null) {
            return null;
        }
        Object value = valueOf(parameter, type);
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return ConversionService.SHARED.convertRequired(value, type);
    }

    @Override
    public <T> T get(String key, Class<T> type, T defaultValue) {
        T value = get(key, type);
        return value == null ? defaultValue : value;
    }

    private static Object valueOf(AnnotationValue<CdiSyntheticParameter> parameter, Class<?> type) {
        boolean array = parameter.booleanValue("array").orElse(false);
        String kind = parameter.stringValue("kind").orElseThrow();
        return switch (kind) {
            case "BOOLEAN" -> {
                boolean[] values = parameter.booleanValues("booleans");
                yield array ? values : (Object) (values.length > 0 && values[0]);
            }
            case "INT" -> {
                int[] values = parameter.intValues("ints");
                yield array ? values : (Object) (values.length > 0 ? values[0] : 0);
            }
            case "LONG" -> {
                long[] values = parameter.longValues("longs");
                yield array ? values : (Object) (values.length > 0 ? values[0] : 0L);
            }
            case "DOUBLE" -> {
                double[] values = parameter.doubleValues("doubles");
                yield array ? values : (Object) (values.length > 0 ? values[0] : 0d);
            }
            case "STRING" -> {
                String[] values = parameter.stringValues("strings");
                yield array ? values : values.length > 0 ? values[0] : "";
            }
            case "ENUM" -> enumsOf(parameter, array, type);
            case "CLASS" -> {
                Class<?>[] values = parameter.classValues("classes");
                yield array ? values : values[0];
            }
            case "ANNOTATION" -> annotationsOf(parameter, array, type);
            case "INVOKER" -> {
                List<AnnotationValue<Annotation>> records = parameter.getAnnotations("invokers");
                RecordedInvoker[] invokers = new RecordedInvoker[records.size()];
                for (int i = 0; i < invokers.length; i++) {
                    invokers[i] = RecordedInvoker.of(records.get(i));
                }
                yield array ? invokers : invokers[0];
            }
            default -> throw new IllegalStateException("The parameter " + parameter.stringValue("name").orElse("")
                + " was recorded as a " + kind + ", which is not a kind of parameter");
        };
    }

    /**
     * An enum constant is recorded by its name. It is converted to the enum it is asked for as, or to the enum
     * it was recorded with where it is asked for as anything wider.
     */
    private static Object enumsOf(AnnotationValue<CdiSyntheticParameter> parameter, boolean array, Class<?> type) {
        String[] names = parameter.stringValues("strings");
        Class<?> recorded = parameter.classValue("type").orElse(null);
        if (!array) {
            Class<?> enumType = type.isEnum() || recorded == null ? type : recorded;
            return ConversionService.SHARED.convertRequired(names[0], enumType);
        }
        Class<?> component = type.isArray() ? type.getComponentType() : null;
        if (component != null && component.isEnum()) {
            return ConversionService.SHARED.convertRequired(names, type);
        }
        Enum<?>[] constants = new Enum<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            Class<?> enumType = recorded == null ? Enum.class : recorded;
            constants[i] = (Enum<?>) ConversionService.SHARED.convertRequired(names[i], enumType);
        }
        return constants;
    }

    /**
     * An annotation is recorded as the values it was written with. Asked for as those values it is handed
     * over as they are; asked for as an annotation it is made an instance of its annotation type.
     */
    private static Object annotationsOf(AnnotationValue<CdiSyntheticParameter> parameter, boolean array,
                                        Class<?> type) {
        List<AnnotationValue<Annotation>> values = parameter.getAnnotations(CdiSyntheticParameter.ANNOTATIONS);
        Class<?> requested = array && type.isArray() ? type.getComponentType() : type;
        if (requested == AnnotationValue.class) {
            return array ? values.toArray(new AnnotationValue<?>[0]) : values.get(0);
        }
        Class<?>[] types = parameter.classValues("classes");
        Annotation[] annotations = new Annotation[values.size()];
        for (int i = 0; i < annotations.length; i++) {
            annotations[i] = CdiAnnotations.annotationOf(annotationType(types[i]), values.get(i));
        }
        return array ? annotations : annotations[0];
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotationType(Class<?> type) {
        return (Class<? extends Annotation>) type;
    }
}
