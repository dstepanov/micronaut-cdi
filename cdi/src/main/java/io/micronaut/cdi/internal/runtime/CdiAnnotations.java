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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationBuilder;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * The places an annotation instance crosses the boundary between the specification's interfaces, which speak
 * of instances, and Micronaut, which records an annotation as the values it was written with.
 *
 * <p>Nothing is read here. An instance is made by the builder generated for its type where the application
 * registered one, and otherwise, like the members of one a program hands over, by {@link CdiReflection}; two
 * annotations are compared as the {@link CdiQualifier} each is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiAnnotations {

    private CdiAnnotations() {
    }

    /**
     * The annotation the given values describe.
     *
     * <p>An annotation type the application listed in {@link io.micronaut.core.annotation.RegisterAnnotations}
     * has a builder generated for it, which makes the instance without reflection. Any other annotation instance
     * is a reflection object: it is made by the module that answers the reflective parts of the specification's
     * API, and asking for one without that module fails with a message that names it.</p>
     *
     * @param beanContext The context of the container asking, whose reflection module makes the instance
     * @param type        The annotation type
     * @param value       The values it was written with, if any were recorded
     * @param <A>         The annotation type
     * @return The annotation
     * @throws UnsupportedOperationException Where the type has no builder and the reflection module is not on the
     *                                       classpath
     */
    public static <A extends Annotation> A annotationOf(BeanContext beanContext, Class<A> type,
                                                       @Nullable AnnotationValue<?> value) {
        AnnotationBuilder<A> builder = builderOf(beanContext, type);
        if (builder != null) {
            return value == null ? builder.build(Map.of()) : builder.build(value);
        }
        String what = "An instance of the annotation " + type.getName();
        return CdiReflection.require(beanContext, what)
            .annotation(type, value);
    }

    /**
     * The builder generated for an annotation type, among the builders of Micronaut, then of the class loader of
     * the annotation type, then of the class loader of the application: a library and its builders can be loaded
     * below Micronaut, and an application that registers a library's annotation below the library.
     *
     * <p>Micronaut's own registry, which reads the class loader Micronaut was loaded by, keeps what it found. A
     * class loader below it is asked only where it is another loader, which is a deployment that isolates its
     * classes, and is asked every time: nothing is kept here.</p>
     *
     * @param beanContext The context of the container asking, whose class loader is the application's
     * @param type        The annotation type
     * @param <A>         The annotation type
     * @return The builder, or {@code null} where none is registered for the type
     */
    public static <A extends Annotation> @Nullable AnnotationBuilder<A> builderOf(BeanContext beanContext,
                                                                                  Class<A> type) {
        AnnotationBuilder<A> builder = AnnotationBuilder.find(type).orElse(null);
        if (builder != null) {
            return builder;
        }
        ClassLoader micronauts = AnnotationBuilder.class.getClassLoader();
        ClassLoader annotations = type.getClassLoader();
        if (annotations != null && annotations != micronauts) {
            builder = AnnotationBuilder.find(type, annotations).orElse(null);
            if (builder != null) {
                return builder;
            }
        }
        ClassLoader application = beanContext.getClassLoader();
        if (application != micronauts && application != annotations) {
            builder = AnnotationBuilder.find(type, application).orElse(null);
        }
        return builder;
    }

    /**
     * The values an annotation instance holds, in the form compiled metadata records an annotation in: the ones an
     * instance that carries its values hands over, the ones the builder generated for its type reads through its
     * accessors, and otherwise the ones the reflection module reads.
     *
     * @param beanContext The context of the container asking
     * @param annotation  The annotation
     * @param what        What reading it is for, named where the reflection module is missing
     * @return The values
     * @throws UnsupportedOperationException Where the annotation has to be read and the reflection module is
     *                                       absent
     */
    @SuppressWarnings("unchecked")
    public static AnnotationValue<?> valuesOf(BeanContext beanContext, Annotation annotation, String what) {
        if (annotation instanceof AnnotationValueProvider<?> provider) {
            return provider.annotationValue();
        }
        Class<Annotation> type = (Class<Annotation>) annotation.annotationType();
        AnnotationBuilder<Annotation> builder = builderOf(beanContext, type);
        if (builder != null) {
            Map<String, Object> members = readByBuilder(builder, annotation);
            if (members != null) {
                Map<CharSequence, Object> recorded = CollectionUtils.newLinkedHashMap(members.size());
                for (Map.Entry<String, Object> member : members.entrySet()) {
                    recorded.put(member.getKey(), recorded(beanContext, member.getValue(), what));
                }
                return new AnnotationValue<>(type.getName(), recorded);
            }
        }
        return CdiReflection.require(beanContext, what).valuesOf(annotation);
    }

    /**
     * The members as the builder reads them, or {@code null} where it was generated by a version of Micronaut
     * that does not read annotations: that builder answers with the default method of the interface, and an
     * accessor of the annotation that fails fails the read.
     */
    private static @Nullable Map<String, Object> readByBuilder(AnnotationBuilder<Annotation> builder,
                                                               Annotation annotation) {
        try {
            return builder.toMap(annotation);
        } catch (UnsupportedOperationException e) {
            StackTraceElement[] trace = e.getStackTrace();
            if (trace.length > 0 && AnnotationBuilder.class.getName().equals(trace[0].getClassName())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * A member value spelt as compiled metadata records it: an enum constant as its name, a class as an
     * {@link AnnotationClassValue}, an annotation as its values, an array element by element.
     */
    private static @Nullable Object recorded(BeanContext beanContext, @Nullable Object value, String what) {
        return switch (value) {
            case Enum<?> constant -> constant.name();
            case Class<?> type -> new AnnotationClassValue<>(type.getName());
            case Annotation nested -> valuesOf(beanContext, nested, what);
            case Object[] array -> recordedArray(beanContext, array, what);
            case null, default -> value;
        };
    }

    private static Object recordedArray(BeanContext beanContext, Object[] array, String what) {
        if (array instanceof Enum<?>[] constants) {
            String[] names = new String[constants.length];
            for (int i = 0; i < constants.length; i++) {
                names[i] = constants[i].name();
            }
            return names;
        }
        if (array instanceof Class<?>[] types) {
            AnnotationClassValue<?>[] classes = new AnnotationClassValue<?>[types.length];
            for (int i = 0; i < types.length; i++) {
                classes[i] = new AnnotationClassValue<>(types[i].getName());
            }
            return classes;
        }
        if (array instanceof Annotation[] nested) {
            AnnotationValue<?>[] values = new AnnotationValue<?>[nested.length];
            for (int i = 0; i < nested.length; i++) {
                values[i] = valuesOf(beanContext, nested[i], what);
            }
            return values;
        }
        return array;
    }

    /**
     * Whether two annotations are the same as far as binding one thing to another goes, which is what the
     * container is asked when it compares two qualifiers or two interceptor bindings: they are of the same
     * type, and every member that takes part in the comparison is equal.
     *
     * @param types What the application was compiled with
     * @param one   The one annotation
     * @param other The other
     * @return Whether they are the same for the purposes of binding
     */
    public static boolean areEquivalent(CompiledAnnotationTypes types, Annotation one, Annotation other) {
        if (!one.annotationType().equals(other.annotationType())) {
            return false;
        }
        return CdiQualifier.ofInstance(types, one).matches(CdiQualifier.ofInstance(types, other));
    }

    /**
     * The hash code of an annotation over the members that take part in the comparison, so that two annotations
     * the container calls the same hash the same.
     *
     * @param types      What the application was compiled with
     * @param annotation The annotation
     * @return The hash code
     */
    public static int bindingHashCode(CompiledAnnotationTypes types, Annotation annotation) {
        return CdiQualifier.ofInstance(types, annotation).bindingHashCode();
    }
}
