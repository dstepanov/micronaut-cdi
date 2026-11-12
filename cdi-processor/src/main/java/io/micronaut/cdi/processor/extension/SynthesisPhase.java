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

import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.metadata.CdiSyntheticDisposer;
import io.micronaut.cdi.internal.metadata.CdiSyntheticInjectionPoint;
import io.micronaut.cdi.internal.metadata.CdiSyntheticObserver;
import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.mapper.NonbindingMembers;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Set<String> SPECIFICATION_FUNCTIONS = Set.of(
        "jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator",
        "jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer");
    private static final String SCOPE = "jakarta.inject.Scope";
    private static final String INJECTION_POINT = "jakarta.enterprise.inject.spi.InjectionPoint";
    private static final String EVENT = "jakarta.enterprise.event.Event";
    private static final String INSTANCE = "jakarta.enterprise.inject.Instance";
    private static final String SYNTHETIC_INJECTIONS =
        "jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections";

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
                Type type = new VisitorTypes(context).of(eventType);
                return observer(type, SyntheticRecords.typeOf(type));
            }

            @Override
            public <T> SyntheticObserverBuilder<T> addObserver(Type eventType) {
                return observer(eventType, SyntheticRecords.typeOf(eventType));
            }

            private <T> SyntheticObserverBuilder<T> observer(
                Type eventType, AnnotationValue<CdiRecordedType> recorded) {
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
            // likewise a reserve, which only a priority selects (section 5.1.2)
            if ((!isAlternative(bean) && !isReserve(bean)) || bean.priority() != null) {
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
        return new SyntheticBeanInfo(bean, scopeOf(bean), isAlternative(bean), context);
    }

    private boolean isAlternative(RecordingBeanBuilder<?> bean) {
        return bean.alternative() || !alternativeStereotypesOf(bean).isEmpty();
    }

    /**
     * Whether the synthetic bean is a reserve: said so, or carrying a stereotype that says so (section 2.8.1).
     */
    static boolean isReserve(RecordingBeanBuilder<?> bean) {
        return bean.reserve() || carriedByAStereotype(bean, Cdi.RESERVE);
    }

    /**
     * Whether the synthetic bean is eagerly initialized: said so, or carrying a stereotype that says so.
     */
    static boolean isEager(RecordingBeanBuilder<?> bean) {
        return bean.eager() || carriedByAStereotype(bean, Cdi.EAGER);
    }

    /**
     * Whether the synthetic bean is auto-closeable: said so, or carrying a stereotype that says so.
     */
    static boolean isAutoClose(RecordingBeanBuilder<?> bean) {
        return bean.autoClose() || carriedByAStereotype(bean, Cdi.AUTO_CLOSE);
    }

    private static boolean carriedByAStereotype(RecordingBeanBuilder<?> bean, String annotation) {
        for (ClassElement stereotype : bean.stereotypes()) {
            // written on the stereotype or on a stereotype it declares, however deep, as a class bean's are read
            if (stereotype.hasStereotype(annotation)) {
                return true;
            }
        }
        return false;
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
        List<AnnotationValue<?>> qualifiers = completed(bean.qualifiers());
        if (qualifiers.isEmpty()) {
            // a bean that names no qualifier has the default one, which the rule of section 2.1.3 says of a
            // bean an extension describes as much as of one a class declares
            qualifiers.add(AnnotationValue.builder(Cdi.DEFAULT).build());
        }
        AnnotationValueBuilder<CdiSyntheticBean> record = AnnotationValue.builder(CdiSyntheticBean.class)
            .member("id", bean.id())
            .member("implementation", new AnnotationClassValue<>(bean.implementation().getName()))
            .member("types", rawTypesOf(bean))
            .member("declaredTypes", beanTypesOf(bean))
            .member(CdiSyntheticBean.QUALIFIERS, qualifiers.toArray(new AnnotationValue<?>[0]))
            .member("qualifierTypes", typesOf(qualifiers));
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
        boolean reserve = isReserve(bean);
        if (reserve && isAlternative(bean)) {
            context.fail("The synthetic bean " + bean.implementation().getName() + " may not be both a reserve "
                + "and an alternative (section 2.8.1)", bean.creator());
        }
        if (reserve) {
            record.member("reserve", true);
        }
        if (isEager(bean)) {
            // only an application scoped bean may be eagerly initialized: any other normal scope, and the dependent
            // pseudo-scope, fails the registration (section 2.9)
            if (!Cdi.APPLICATION_SCOPED.equals(scope.name()) && (scope.normal() || Cdi.DEPENDENT.equals(scope.name()))) {
                context.fail("The synthetic bean " + bean.implementation().getName() + " is eagerly initialized, "
                    + "which only an @ApplicationScoped bean may be; it is in " + scope.name() + " (section 2.9)",
                    bean.creator());
            }
            record.member("eager", true);
        }
        if (isAutoClose(bean)) {
            record.member("autoClose", true);
        }
        if (!bean.injectionPoints().isEmpty()) {
            List<AnnotationValue<CdiSyntheticInjectionPoint>> points = new ArrayList<>();
            for (RecordingBeanBuilder.InjectionPoint point : bean.injectionPoints()) {
                String problem = problemOf(point, scope);
                if (problem != null) {
                    context.fail("The injection point " + point.type() + " registered for the synthetic bean "
                        + bean.implementation().getName() + " is illegal: " + problem, bean.creator());
                }
                List<AnnotationValue<?>> pointQualifiers = completed(point.qualifiers());
                points.add(AnnotationValue.builder(CdiSyntheticInjectionPoint.class)
                    .member("type", SyntheticRecords.typeOf(point.type()))
                    .member(CdiSyntheticInjectionPoint.QUALIFIERS, pointQualifiers.toArray(new AnnotationValue<?>[0]))
                    .member("qualifierTypes", typesOf(pointQualifiers))
                    .build());
            }
            record.member("injectionPoints", points.toArray(new AnnotationValue<?>[0]));
        }
        // which of the two methods the creation and destruction functions implement, read off the classes the
        // extension named: the container calls the one that exists (CDI 5.0), whether the class declares it or
        // inherits it
        if (takesInjections(bean.creator(), "create")) {
            record.member("creatorTakesInjections", true);
        }
        ClassElement disposerClass = bean.disposer();
        if (disposerClass != null && takesInjections(disposerClass, "dispose")) {
            record.member("disposerTakesInjections", true);
        }
        return record.build();
    }

    /**
     * What the rules of an injection point make a definition error of a point registered for a synthetic bean, as
     * they do of a point a class declares: a type variable; a raw {@code Event} or {@code Instance}, an
     * {@code Event<?>} or {@code Event<? extends X>}, an {@code Instance<? super X>} and an {@code Instance} of such an
     * event (sections 5.6.1 and 9.2); and the {@code @Default InjectionPoint} of a bean that is not dependent, which
     * outlives any one point it is injected into (section 6.5.3).
     */
    private static @Nullable String problemOf(RecordingBeanBuilder.InjectionPoint point, Scope scope) {
        Type type = point.type();
        if (type.isTypeVariable()) {
            return "a type variable is not a legal injection point type (section 5.2.3)";
        }
        String raw = rawNameOf(type);
        if (INJECTION_POINT.equals(raw) && !Cdi.DEPENDENT.equals(scope.name())
            && (point.qualifiers().isEmpty() || point.qualifiers().stream()
                .anyMatch(qualifier -> Cdi.DEFAULT.equals(qualifier.getAnnotationName())))) {
            return "only a dependent bean may be told the injection point it is injected into (section 6.5.3)";
        }
        if (raw != null && (EVENT.equals(raw) || INSTANCE.equals(raw))) {
            return builtInProblemOf(type, raw, true);
        }
        return null;
    }

    private static @Nullable String builtInProblemOf(Type type, String raw, boolean outer) {
        if (type.isClass()) {
            return "a raw " + raw + " names no type";
        }
        Type argument = type.asParameterizedType().typeArguments().get(0);
        if (EVENT.equals(raw)) {
            if (argument.isWildcardType() && argument.asWildcardType().lowerBound() == null) {
                return "an Event<?> or Event<? extends X> names no type to fire (section 9.2)";
            }
            return null;
        }
        if (argument.isWildcardType()) {
            if (argument.asWildcardType().lowerBound() != null) {
                return "an Instance<? super X> names no type to look up (section 5.6.1)";
            }
            argument = argument.asWildcardType().upperBound();
        }
        if (outer && argument != null && EVENT.equals(rawNameOf(argument))) {
            return builtInProblemOf(argument, EVENT, false);
        }
        return null;
    }

    private static @Nullable String rawNameOf(Type type) {
        if (type.isClass()) {
            return type.asClass().declaration().name();
        }
        if (type.isParameterizedType()) {
            return type.asParameterizedType().declaration().name();
        }
        return null;
    }

    /**
     * Whether the function implements the method that takes {@code SyntheticInjections}, declared on its class or
     * inherited from a superclass or an interface of its own; the default of the specification's interface, which
     * throws, is no implementation.
     */
    private static boolean takesInjections(ClassElement function, String methodName) {
        return function.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance().named(methodName))
            .stream()
            .anyMatch(method -> !method.isAbstract()
                && !SPECIFICATION_FUNCTIONS.contains(method.getDeclaringType().getName())
                && Arrays.stream(method.getParameters()).anyMatch(
                    parameter -> SYNTHETIC_INJECTIONS.equals(parameter.getGenericType().getName())));
    }

    private AnnotationValue<CdiSyntheticObserver> recordOf(RecordingObserverBuilder<?> observer) {
        AnnotationValueBuilder<CdiSyntheticObserver> record = AnnotationValue.builder(CdiSyntheticObserver.class)
            .member("id", observer.id())
            .member(CdiSyntheticObserver.EVENT_TYPE, observer.recordedEventType())
            .member("priority", observer.priority());
        ClassElement declaringClass = observer.declaringClassIfKnown();
        if (declaringClass != null) {
            // the class the registration phase reported the observer with, which the running container reports
            // too where the class is on its classpath
            record.member("declaringClass", new AnnotationClassValue<>(declaringClass.getName()));
        }
        if (!observer.qualifiers().isEmpty()) {
            List<AnnotationValue<?>> qualifiers = completed(observer.qualifiers());
            record.member(CdiSyntheticObserver.QUALIFIERS, qualifiers.toArray(new AnnotationValue<?>[0]));
            record.member("qualifierTypes", typesOf(qualifiers));
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

    /**
     * The qualifiers an extension gave a component, each with the members that take no part in resolution listed
     * under {@code $nonBinding}, as the compiler lists them on a qualifier it reads: the members the annotation
     * interface marks as the compilation holds it, the ones an extension marked during discovery among them.
     */
    private List<AnnotationValue<?>> completed(List<AnnotationValue<?>> qualifiers) {
        List<AnnotationValue<?>> completed = new ArrayList<>(qualifiers.size());
        for (AnnotationValue<?> qualifier : qualifiers) {
            completed.add(NonbindingMembers.complete(qualifier, context));
        }
        return completed;
    }

    private static AnnotationClassValue<?>[] typesOf(List<AnnotationValue<?>> annotations) {
        AnnotationClassValue<?>[] types = new AnnotationClassValue<?>[annotations.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = new AnnotationClassValue<>(annotations.get(i).getAnnotationName());
        }
        return types;
    }

    private static AnnotationClassValue<?>[] rawTypesOf(RecordingBeanBuilder<?> bean) {
        List<ClassElement> classes = new ArrayList<>();
        for (Type type : bean.types()) {
            classes.add(ElementTypes.elementOf(type));
        }
        return classValues(classes);
    }

    private static AnnotationValue<?>[] beanTypesOf(RecordingBeanBuilder<?> bean) {
        List<Type> types = bean.types();
        AnnotationValue<?>[] records = new AnnotationValue<?>[types.size()];
        for (int i = 0; i < records.length; i++) {
            records[i] = SyntheticRecords.beanTypeOf(types.get(i));
        }
        return records;
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
