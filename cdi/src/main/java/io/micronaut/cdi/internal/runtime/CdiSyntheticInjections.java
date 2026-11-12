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

import io.micronaut.cdi.internal.metadata.CdiDeclaredTypes;
import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiSyntheticInjectionPoint;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the creation and the destruction function of a synthetic bean look beans up through (CDI 5.0,
 * {@code SyntheticInjections}): the injection points the extension registered for the bean, and only those.
 *
 * <p>A lookup is the lookup the function was handed before CDI 5.0, narrowed to a registered type and qualifiers,
 * so that what it creates belongs to the same owner: the dependent instances a creation obtains are destroyed with
 * the synthetic bean, and the ones a disposal obtains as it returns.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiSyntheticInjections implements SyntheticInjections {

    /**
     * The member of the record of a synthetic bean's declared types that carries the injection points the extension
     * registered for it, on its runtime definition.
     */
    public static final String INJECTION_POINTS = "injectionPoints";

    private final CdiInstance<Object> lookup;
    private final CdiInstance<Object> pointed;
    private final List<Registered> registered;
    private final CompiledAnnotationTypes types;
    private final boolean disposal;

    private CdiSyntheticInjections(CdiInstance<Object> lookup, CdiInstance<Object> pointed, List<Registered> registered,
                                   CompiledAnnotationTypes types, boolean disposal) {
        this.lookup = lookup;
        this.pointed = pointed;
        this.registered = registered;
        this.types = types;
        this.disposal = disposal;
    }

    /**
     * The injections of one creation or disposal.
     *
     * @param beanContext The context
     * @param lookup      The lookup the function would have been handed, owning what it creates
     * @param bean        The synthetic bean, which the injection point of a dependent instance obtained belongs to,
     *                    or {@code null} where it is not known
     * @param points      The recorded injection points
     * @param disposal    Whether it is a disposal, which may not look up {@code InjectionPoint}
     * @return The injections
     */
    public static CdiSyntheticInjections of(BeanContext beanContext, CdiInstance<Object> lookup,
                                            @Nullable Bean<?> bean,
                                            List<AnnotationValue<CdiSyntheticInjectionPoint>> points,
                                            boolean disposal) {
        CompiledAnnotationTypes types = CompiledAnnotationTypes.of(beanContext);
        return new CdiSyntheticInjections(lookup, bean == null ? lookup : lookup.pointingAt(bean),
            registeredOf(types, points), types, disposal);
    }

    /**
     * Validates the recorded injection points as the container starts (CDI 5.0, section 12.5): each must resolve
     * to exactly one bean, as an injection point of a bean must. {@code InjectionPoint} itself is the built-in bean
     * of a dependent bean's metadata, which needs no bean of its own.
     *
     * @param beanContext    The context
     * @param implementation The implementation class of the synthetic bean, for the report
     * @param points         The recorded injection points
     * @throws DeploymentException Where one is unsatisfied or ambiguous
     */
    public static void validate(BeanContext beanContext, String implementation,
                                List<AnnotationValue<CdiSyntheticInjectionPoint>> points) {
        if (points.isEmpty()) {
            return;
        }
        CompiledAnnotationTypes types = CompiledAnnotationTypes.of(beanContext);
        try (CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT)) {
            for (Registered point : registeredOf(types, points)) {
                if (point.type().getType() == InjectionPoint.class) {
                    continue;
                }
                CdiInstance<?> selected = lookup.selectArgument(point.type(), point.qualifiers());
                if (selected.isUnsatisfied()) {
                    throw new DeploymentException("The injection point " + point + " registered for the synthetic "
                        + "bean " + implementation + " has no bean to satisfy it");
                }
                if (selected.isAmbiguous()) {
                    throw new DeploymentException("The injection point " + point + " registered for the synthetic "
                        + "bean " + implementation + " is ambiguous");
                }
            }
        }
    }

    /**
     * The injection points of a synthetic bean as the specification describes a bean's: the ones its extension
     * registered, each of the type and with the qualifiers it was registered with, belonging to the bean, with no
     * member and no annotated model.
     *
     * @param beanContext The context
     * @param bean        The synthetic bean
     * @param definition  Its definition
     * @return The injection points, none where the bean is not synthetic or registered none
     */
    static Set<InjectionPoint> injectionPointsOf(BeanContext beanContext, Bean<?> bean, BeanDefinition<?> definition) {
        AnnotationValue<CdiDeclaredTypes> declared = definition.getAnnotationMetadata()
            .getAnnotation(CdiDeclaredTypes.class);
        if (declared == null) {
            return Set.of();
        }
        List<AnnotationValue<CdiSyntheticInjectionPoint>> points =
            declared.getAnnotations(INJECTION_POINTS, CdiSyntheticInjectionPoint.class);
        Set<InjectionPoint> described = new LinkedHashSet<>(points.size());
        for (AnnotationValue<CdiSyntheticInjectionPoint> point : points) {
            Argument<?> type = RecordedTypes.typeOf(point.getAnnotation("type", CdiRecordedType.class)
                .orElseThrow(() -> new IllegalStateException("A synthetic injection point was recorded without a type")));
            MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
            for (AnnotationValue<Annotation> qualifier : point.getAnnotations(CdiSyntheticInjectionPoint.QUALIFIERS)) {
                metadata.addDeclaredAnnotation(qualifier.getAnnotationName(), qualifier.getValues());
                metadata.addDeclaredStereotype(List.of(qualifier.getAnnotationName()), AnnotationUtil.QUALIFIER,
                    Map.of());
            }
            described.add(new CdiInjectionPoint(beanContext, bean,
                Argument.of(type.getType(), type.getName(), metadata, type.getTypeParameters()), null));
        }
        return described;
    }

    private static List<Registered> registeredOf(CompiledAnnotationTypes types,
                                                 List<AnnotationValue<CdiSyntheticInjectionPoint>> points) {
        List<Registered> registered = new ArrayList<>(points.size());
        for (AnnotationValue<CdiSyntheticInjectionPoint> point : points) {
            AnnotationValue<CdiRecordedType> type = point.getAnnotation("type", CdiRecordedType.class)
                .orElseThrow(() -> new IllegalStateException("A synthetic injection point was recorded without a type"));
            List<CdiQualifier> qualifiers = new ArrayList<>();
            for (AnnotationValue<Annotation> qualifier : point.getAnnotations(CdiSyntheticInjectionPoint.QUALIFIERS)) {
                qualifiers.add(CdiQualifier.ofValue(types, qualifier));
            }
            registered.add(new Registered(RecordedTypes.typeOf(type), orDefault(types, qualifiers)));
        }
        return registered;
    }

    private static List<CdiQualifier> orDefault(CompiledAnnotationTypes types, List<CdiQualifier> qualifiers) {
        // no qualifier stands for @Default, as at an injection point
        return qualifiers.isEmpty()
            ? List.of(CdiQualifier.ofValue(types, AnnotationValue.builder(Default.class).build()))
            : qualifiers;
    }

    @Override
    public <T> T get(Class<T> type, Annotation... qualifiers) {
        return get(Argument.of(type), qualifiers);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(TypeLiteral<T> type, Annotation... qualifiers) {
        return get((Argument<T>) Argument.of(type.getType()), qualifiers);
    }

    private <T> T get(Argument<T> type, Annotation @Nullable [] qualifiers) {
        List<CdiQualifier> required = orDefault(types,
            qualifiers == null ? List.of() : CdiQualifier.ofInstances(types, qualifiers));
        if (disposal && type.getType() == InjectionPoint.class) {
            throw new IllegalArgumentException("A disposal may not look up the InjectionPoint of a synthetic bean");
        }
        for (Registered point : registered) {
            if (point.type().equalsStructure(type) && point.hasQualifiers(required)) {
                // the metadata of the injection point the bean is created for is the creation's own; anything else
                // is looked up as an injection point of the synthetic bean (CDI 5.0, section 12.5)
                CdiInstance<Object> through = type.getType() == InjectionPoint.class ? lookup : pointed;
                return through.selectArgument(type, required).get();
            }
        }
        throw new IllegalArgumentException("No injection point of " + type.getTypeName() + " with the qualifiers "
            + required + " was registered for the synthetic bean with SyntheticBeanBuilder.withInjectionPoint()");
    }

    /**
     * An injection point the extension registered.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers, {@code @Default} where none was given
     */
    private record Registered(Argument<?> type, List<CdiQualifier> qualifiers) {

        boolean hasQualifiers(List<CdiQualifier> required) {
            if (required.size() != qualifiers.size()) {
                return false;
            }
            for (CdiQualifier qualifier : required) {
                if (!qualifier.isAmong(qualifiers)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return qualifiers + " " + type.getTypeName();
        }
    }
}
