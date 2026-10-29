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
package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.cdi.internal.runtime.CdiAnnotations;
import io.micronaut.cdi.internal.runtime.RecordedInvoker;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.util.CollectionUtils;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    // the container the component was handed these by: its invokers invoke its beans, and an annotation asked
    // for as an instance is made by its reflection module
    private final BeanContext beanContext;
    private final Map<String, AnnotationValue<CdiSyntheticParameter>> parameters;
    /**
     * The invokers of each parameter that holds them, read with the parameters: an invoker finds the method it
     * invokes the first time it is invoked and keeps it, which a new one read for each {@code get} would do again
     * every time. Reading one names the method and resolves nothing.
     */
    private final Map<String, RecordedInvoker[]> invokers;

    CdiParameters(BeanContext beanContext,
                  List<AnnotationValue<CdiSyntheticParameter>> recorded) {
        this.beanContext = beanContext;
        Map<String, AnnotationValue<CdiSyntheticParameter>> byName = CollectionUtils.newLinkedHashMap(recorded.size());
        for (AnnotationValue<CdiSyntheticParameter> parameter : recorded) {
            // a key recorded twice is the last value recorded for it
            byName.put(parameter.stringValue("name").orElseThrow(), parameter);
        }
        this.parameters = Collections.unmodifiableMap(byName);
        Map<String, RecordedInvoker[]> read = CollectionUtils.newLinkedHashMap(byName.size());
        for (Map.Entry<String, AnnotationValue<CdiSyntheticParameter>> parameter : byName.entrySet()) {
            if ("INVOKER".equals(parameter.getValue().stringValue("kind").orElse(null))) {
                read.put(parameter.getKey(), readInvokers(beanContext, parameter.getValue()));
            }
        }
        this.invokers = read.isEmpty() ? Map.of() : Collections.unmodifiableMap(read);
    }

    private static RecordedInvoker[] readInvokers(BeanContext beanContext,
                                                  AnnotationValue<CdiSyntheticParameter> parameter) {
        List<AnnotationValue<Annotation>> records = parameter.getAnnotations(CdiSyntheticParameter.INVOKERS);
        RecordedInvoker[] read = new RecordedInvoker[records.size()];
        for (int i = 0; i < read.length; i++) {
            read[i] = RecordedInvoker.of(beanContext, records.get(i));
        }
        return read;
    }

    /**
     * The invokers among the parameters, which the container checks the lookups of as it starts.
     *
     * @return The invokers
     */
    List<RecordedInvoker> invokers() {
        List<RecordedInvoker> all = new ArrayList<>();
        for (RecordedInvoker[] read : invokers.values()) {
            all.addAll(Arrays.asList(read));
        }
        return all;
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

    private Object valueOf(AnnotationValue<CdiSyntheticParameter> parameter, Class<?> type) {
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
                RecordedInvoker[] read = Objects.requireNonNull(
                    invokers.get(parameter.stringValue("name").orElseThrow()), "the invokers read with the parameter");
                // an array handed out is the caller's to change
                yield array ? read.clone() : read[0];
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
    private Object annotationsOf(AnnotationValue<CdiSyntheticParameter> parameter, boolean array, Class<?> type) {
        List<AnnotationValue<Annotation>> values = parameter.getAnnotations(CdiSyntheticParameter.ANNOTATIONS);
        Class<?> requested = array && type.isArray() ? type.getComponentType() : type;
        if (requested == AnnotationValue.class) {
            return array ? values.toArray(new AnnotationValue<?>[0]) : values.get(0);
        }
        Class<?>[] types = parameter.classValues("classes");
        Annotation[] annotations = new Annotation[values.size()];
        for (int i = 0; i < annotations.length; i++) {
            annotations[i] = CdiAnnotations.annotationOf(beanContext, annotationType(types[i]), values.get(i));
        }
        return array ? annotations : annotations[0];
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotationType(Class<?> type) {
        return (Class<? extends Annotation>) type;
    }
}
