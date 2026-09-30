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

import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.annotation.CdiSyntheticBean;
import io.micronaut.cdi.annotation.CdiSyntheticDisposer;
import io.micronaut.cdi.annotation.CdiSyntheticObserver;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The synthesis phase of section 2.10.5 for one compilation: what the extensions described, and the bean
 * definitions it is written as.
 *
 * <p>The phase runs inside the compiler, after every class of the compilation has been described to the
 * registration phase. A synthetic bean or observer is gathered here as the extension describes it, described to
 * the registration phase in turn, and written as a bean definition of the creator or observer class the
 * extension named, with everything else the extension said as the definition's annotation metadata. The
 * classes the extension named therefore become something the container instantiates through a definition, and
 * the extension itself is needed by nothing that runs later.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SynthesisPhase {

    private static final String SCOPE = "jakarta.inject.Scope";

    private final VisitorContext context;
    private final DiscoveredClasses discovered;
    private final List<RecordingBeanBuilder<?>> beans = new ArrayList<>();
    private final List<RecordingObserverBuilder<?>> observers = new ArrayList<>();
    private final Map<String, Integer> ordinals = new LinkedHashMap<>();

    SynthesisPhase(VisitorContext context, DiscoveredClasses discovered) {
        this.context = context;
        this.discovered = discovered;
    }

    /**
     * What one extension adds components through. The components are identified by the extension and the order
     * it described them in, so that two compilations of one application that both ran the extension record the
     * same component, which the container registers once.
     *
     * @param extension The extension
     * @return The components of the extension
     */
    SyntheticComponents componentsOf(BuildCompatibleExtension extension) {
        String extensionName = extension.getClass().getName();
        return new SyntheticComponents() {
            @Override
            public <T> SyntheticBeanBuilder<T> addBean(Class<T> implementationClass) {
                RecordingBeanBuilder<T> builder = new RecordingBeanBuilder<>(context, nextId(extensionName),
                    implementationClass);
                beans.add(builder);
                return builder;
            }

            @Override
            public <T> SyntheticObserverBuilder<T> addObserver(Class<T> eventType) {
                return observer(new VisitorTypes(context).of(eventType), SyntheticRecords.typeOf(eventType));
            }

            @Override
            public <T> SyntheticObserverBuilder<T> addObserver(Type eventType) {
                return observer(eventType, SyntheticRecords.typeOf(eventType));
            }

            private <T> SyntheticObserverBuilder<T> observer(
                Type eventType, AnnotationValue<io.micronaut.cdi.annotation.CdiRecordedType> recorded) {
                RecordingObserverBuilder<T> builder = new RecordingObserverBuilder<>(context,
                    nextId(extensionName), eventType, recorded,
                    // the class an observer is declared by is the extension's, unless the extension names one
                    context.getClassElement(extensionName)
                        .or(() -> context.getClassElement(extensionName.replace('$', '.'))).orElse(null));
                observers.add(builder);
                return builder;
            }
        };
    }

    private String nextId(String extensionName) {
        return extensionName + "#" + ordinals.merge(extensionName, 1, Integer::sum);
    }

    /**
     * The beans the extensions described that are beans of the deployment: an alternative no priority selected
     * is not enabled, exactly as a compiled one is not (section 2.1.7).
     */
    List<RecordingBeanBuilder<?>> enabledBeans() {
        List<RecordingBeanBuilder<?>> enabled = new ArrayList<>();
        for (RecordingBeanBuilder<?> bean : beans) {
            if (!isAlternative(bean) || bean.priority() != null) {
                enabled.add(bean);
            }
        }
        return enabled;
    }

    List<RecordingObserverBuilder<?>> observers() {
        return observers;
    }

    /**
     * The synthetic bean as the registration phase is told about it.
     */
    SyntheticBeanInfo describe(RecordingBeanBuilder<?> bean) {
        return new SyntheticBeanInfo(bean, scopeOf(bean), isAlternative(bean));
    }

    private boolean isAlternative(RecordingBeanBuilder<?> bean) {
        return bean.alternative() || !alternativeStereotypesOf(bean).isEmpty();
    }

    private static List<String> alternativeStereotypesOf(RecordingBeanBuilder<?> bean) {
        List<String> alternatives = new ArrayList<>();
        for (ClassElement stereotype : bean.stereotypes()) {
            if (stereotype.hasDeclaredAnnotation(Cdi.ALTERNATIVE)) {
                alternatives.add(stereotype.getName());
            }
        }
        return alternatives;
    }

    /**
     * The scope of the synthetic bean: the one the extension set, or the one a stereotype it named carries, or
     * the dependent pseudo-scope.
     */
    Scope scopeOf(RecordingBeanBuilder<?> bean) {
        ClassElement scope = bean.scope();
        if (scope == null) {
            for (ClassElement stereotype : bean.stereotypes()) {
                scope = scopeCarriedBy(stereotype);
                if (scope != null) {
                    break;
                }
            }
        }
        if (scope == null) {
            return new Scope(Cdi.DEPENDENT, false, null);
        }
        boolean normal = scope.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE) || discovered.isNormalContext(scope.getName());
        return new Scope(scope.getName(), normal, scope);
    }

    private @Nullable ClassElement scopeCarriedBy(ClassElement stereotype) {
        // a scope of the specification is recorded by name as it is read into the Micronaut scope of the same
        // meaning, on a stereotype as on a bean
        String recorded = stereotype.getAnnotationMetadata().stringValue(CdiScope.class).orElse(null);
        if (recorded != null) {
            ClassElement scope = context.getClassElement(recorded).orElse(null);
            if (scope != null) {
                return scope;
            }
        }
        for (String declared : stereotype.getDeclaredAnnotationNames()) {
            ClassElement annotation = context.getClassElement(declared).orElse(null);
            if (annotation != null && !declared.startsWith("io.micronaut.")
                && (annotation.hasDeclaredAnnotation(SCOPE) || annotation.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE))) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * What the extensions described, as the classes the container is to instantiate and the record each of
     * their definitions carries: the creator class of each synthetic bean with the bean's record, its disposer
     * class, and the observer class of each synthetic observer.
     *
     * @return The classes, each with the record of its definition
     */
    List<Component> components() {
        List<Component> components = new ArrayList<>();
        for (RecordingBeanBuilder<?> bean : enabledBeans()) {
            components.add(new Component(bean.creator(), recordOf(bean)));
            ClassElement disposer = bean.disposer();
            if (disposer != null) {
                components.add(new Component(disposer, AnnotationValue.builder(CdiSyntheticDisposer.class)
                    .value(bean.id()).build()));
            }
        }
        for (RecordingObserverBuilder<?> observer : observers) {
            components.add(new Component(observer.observer(), recordOf(observer)));
        }
        return components;
    }

    private AnnotationValue<CdiSyntheticBean> recordOf(RecordingBeanBuilder<?> bean) {
        List<AnnotationValue<?>> qualifiers = new ArrayList<>(bean.qualifiers());
        if (qualifiers.isEmpty()) {
            // a bean that names no qualifier has the default one, which the rule of section 2.1.3 says of a
            // bean an extension describes as much as of one a class declares
            qualifiers.add(AnnotationValue.builder("jakarta.enterprise.inject.Default").build());
        }
        AnnotationValueBuilder<CdiSyntheticBean> record = AnnotationValue.builder(CdiSyntheticBean.class)
            .member("id", bean.id())
            .member("implementation", new AnnotationClassValue<>(bean.implementation().getName()))
            .member("types", classValues(bean.types()))
            .member(CdiSyntheticBean.QUALIFIERS, qualifiers.toArray(new AnnotationValue<?>[0]))
            .member("qualifierTypes", typesOf(qualifiers))
            .member("nonbinding", nonbindingOf(qualifiers));
        Scope scope = scopeOf(bean);
        if (scope.annotation() != null) {
            record.member("scope", new AnnotationClassValue<>(scope.name())).member("normal", scope.normal());
        }
        String name = bean.name();
        if (name != null) {
            record.member("name", name);
        }
        Integer priority = bean.priority();
        if (priority != null) {
            record.member("priority", new int[] {priority});
        }
        if (bean.alternative()) {
            record.member("alternative", true);
        }
        if (!bean.stereotypes().isEmpty()) {
            record.member("stereotypes", classValues(bean.stereotypes()));
            record.member("alternativeStereotypes", alternativeStereotypesOf(bean).toArray(new String[0]));
        }
        if (bean.disposer() != null) {
            record.member("disposer", true);
        }
        if (!bean.parameters().isEmpty()) {
            record.member("params", bean.parameters().toArray(new AnnotationValue<?>[0]));
        }
        return record.build();
    }

    private AnnotationValue<CdiSyntheticObserver> recordOf(RecordingObserverBuilder<?> observer) {
        AnnotationValueBuilder<CdiSyntheticObserver> record = AnnotationValue.builder(CdiSyntheticObserver.class)
            .member("id", observer.id())
            .member(CdiSyntheticObserver.EVENT_TYPE, observer.recordedEventType())
            .member("priority", observer.priority());
        if (!observer.qualifiers().isEmpty()) {
            record.member(CdiSyntheticObserver.QUALIFIERS, observer.qualifiers().toArray(new AnnotationValue<?>[0]));
            record.member("qualifierTypes", typesOf(observer.qualifiers()));
            record.member("nonbinding", nonbindingOf(observer.qualifiers()));
        }
        if (observer.async()) {
            record.member("async", true);
        }
        record.member("transactionPhase", observer.transactionPhase().name());
        if (!observer.parameters().isEmpty()) {
            record.member("params", observer.parameters().toArray(new AnnotationValue<?>[0]));
        }
        return record.build();
    }

    private String[] nonbindingOf(List<AnnotationValue<?>> qualifiers) {
        List<String> nonbinding = new ArrayList<>();
        for (AnnotationValue<?> qualifier : qualifiers) {
            nonbinding.addAll(SyntheticRecords.nonbindingMembersOf(context, discovered, qualifier.getAnnotationName()));
        }
        return nonbinding.toArray(new String[0]);
    }

    private static AnnotationClassValue<?>[] typesOf(List<AnnotationValue<?>> annotations) {
        AnnotationClassValue<?>[] types = new AnnotationClassValue<?>[annotations.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = new AnnotationClassValue<>(annotations.get(i).getAnnotationName());
        }
        return types;
    }

    private static AnnotationClassValue<?>[] classValues(List<ClassElement> classes) {
        AnnotationClassValue<?>[] values = new AnnotationClassValue<?>[classes.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = new AnnotationClassValue<>(classes.get(i).getName());
        }
        return values;
    }

    /**
     * The scope of a synthetic bean.
     *
     * @param name       The name of the scope annotation
     * @param normal     Whether the scope is a normal one
     * @param annotation The scope annotation, or {@code null} for the dependent pseudo-scope a bean that names
     *                   no scope is in
     */
    record Scope(String name, boolean normal, @Nullable ClassElement annotation) {
    }

    /**
     * A class an extension named for the container to instantiate, and what its definition records.
     *
     * @param type   The class
     * @param record The record
     */
    record Component(ClassElement type, AnnotationValue<?> record) {
    }
}
