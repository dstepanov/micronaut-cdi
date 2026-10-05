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

import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.metadata.CdiSyntheticDisposer;
import io.micronaut.cdi.internal.metadata.CdiSyntheticObserver;
import io.micronaut.cdi.internal.metadata.CdiSyntheticParameter;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiInjectionPoint;
import io.micronaut.cdi.internal.runtime.CdiInstance;
import io.micronaut.cdi.internal.runtime.CdiQualifiers;
import io.micronaut.cdi.internal.runtime.CurrentInjectionPoint;
import io.micronaut.cdi.internal.runtime.ObserverRegistry;
import io.micronaut.cdi.internal.runtime.RecordedInvoker;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers the synthetic beans and observers the build compatible extensions described (section 2.10.5).
 *
 * <p>Every phase of an extension runs while the application compiles, the synthesis phase included: the
 * extension is not on the classpath of the running application, and nothing of it is loaded or invoked here.
 * What the phase described was recorded as the annotation metadata of bean definitions the compiler generated
 * for the creator, disposer and observer classes the extension named. This reads those records as the container
 * starts, registers a bean for each synthetic bean and an observer for each synthetic observer, and obtains a
 * creator, a disposer or an observer from the definition that was generated for it.</p>
 *
 * <p>It runs as early as anything can: registering a bean changes the object graph, so it has to happen before
 * anything has been resolved from it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Context
@Order(Ordered.HIGHEST_PRECEDENCE)
@Internal
public final class RecordedSynthesis {

    private static final String DEPENDENT = "jakarta.enterprise.context.Dependent";

    private final BeanContext beanContext;

    /**
     * The lookup handed to each synthetic instance's creation function, kept until the instance is destroyed:
     * the dependent instances it obtained are the created instance's own (section 2.10.5).
     */
    private final Map<Object, CdiInstance<Object>> creatorLookups =
        Collections.synchronizedMap(new IdentityHashMap<>());

    public RecordedSynthesis(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @PostConstruct
    void register() {
        // a record is identified by the extension that described it, so that a bean two compilations of one
        // application both recorded - each ran the extension - is one bean
        Map<String, BeanDefinition<?>> beans = new LinkedHashMap<>();
        Map<String, BeanDefinition<?>> disposers = new LinkedHashMap<>();
        Map<String, BeanDefinition<?>> observers = new LinkedHashMap<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            AnnotationMetadata metadata = definition.getAnnotationMetadata();
            metadata.findAnnotation(CdiSyntheticBean.class).ifPresent(record ->
                beans.putIfAbsent(record.stringValue("id").orElseThrow(), definition));
            metadata.findAnnotation(CdiSyntheticDisposer.class).ifPresent(record ->
                disposers.putIfAbsent(record.stringValue().orElseThrow(), definition));
            metadata.findAnnotation(CdiSyntheticObserver.class).ifPresent(record ->
                observers.putIfAbsent(record.stringValue("id").orElseThrow(), definition));
        }
        if (beans.isEmpty() && observers.isEmpty()) {
            return;
        }
        List<RecordedInvoker> invokers = new java.util.ArrayList<>();
        for (Map.Entry<String, BeanDefinition<?>> bean : beans.entrySet()) {
            AnnotationValue<CdiSyntheticBean> record =
                bean.getValue().getAnnotationMetadata().getAnnotation(CdiSyntheticBean.class);
            if (record == null) {
                continue;
            }
            CdiParameters parameters = new CdiParameters(record.getAnnotations("params", CdiSyntheticParameter.class));
            invokers.addAll(parameters.invokers());
            BeanDefinition<?> disposer = disposers.get(bean.getKey());
            if (disposer == null && record.booleanValue("disposer").orElse(false)) {
                throw new IllegalStateException("The synthetic bean " + record.stringValue("implementation").orElse("")
                    + " was recorded with a disposer, and the definition of the disposer is not among the beans "
                    + "of the application: the factory generated with it is not on the classpath");
            }
            register(record, parameters, bean.getValue(), disposer);
        }
        ObserverRegistry observerRegistry = beanContext.getBean(ObserverRegistry.class);
        for (BeanDefinition<?> observer : observers.values()) {
            AnnotationValue<CdiSyntheticObserver> record =
                observer.getAnnotationMetadata().getAnnotation(CdiSyntheticObserver.class);
            if (record != null) {
                observerRegistry.registerSynthetic(new SyntheticObserverMethod<>(beanContext, observer, record));
            }
        }
        if (!beans.isEmpty()) {
            // the container may have read its beans already: what was just registered has to be seen
            beanContext.findBean(CdiBeanContainer.class).ifPresent(CdiBeanContainer::refreshCandidates);
        }
        // an invoker an extension built and handed to a synthetic component names lookups; whether they
        // resolve is a property of the deployment, and is checked as it comes up rather than at the first
        // invocation (CDI 4.1, chapter 7)
        for (RecordedInvoker invoker : invokers) {
            invoker.validateLookups(beanContext);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void register(AnnotationValue<CdiSyntheticBean> record,
                              CdiParameters parameters,
                              BeanDefinition<?> creator,
                              @Nullable BeanDefinition<?> disposer) {
        Class<T> implementation = (Class<T>) record.classValue("implementation").orElseThrow(() ->
            new IllegalStateException("The implementation class " + record.stringValue("implementation").orElse("")
                + " of a synthetic bean is not on the classpath"));
        Class<? extends Annotation> scope = scopeOf(record);
        RuntimeBeanDefinition.Builder<T> builder = RuntimeBeanDefinition
            .builder(Argument.of(implementation), creationContext ->
                create(implementation, scope, (BeanDefinition<SyntheticBeanCreator<T>>) creator, parameters,
                    creationContext));
        List<AnnotationValue<Annotation>> qualifiers = record.getAnnotations(CdiSyntheticBean.QUALIFIERS);
        Qualifier<T> qualifier = CdiQualifiers.ofValues(qualifiers, Set.of(record.stringValues("nonbinding")));
        String name = record.stringValue("name").filter(given -> !given.isEmpty()).orElse(null);
        if (name != null) {
            // the name is a qualifier beside the others, not in their place: naming the builder would replace
            // what was just given it
            Qualifier<T> named = Qualifiers.byName(name);
            qualifier = qualifier == null ? named : Qualifiers.byQualifiers(qualifier, named);
        }
        builder.qualifier(qualifier);
        builder.annotationMetadata(metadataOf(record, qualifiers, name, scope));
        if (scope != null && scope.getName().equals("jakarta.inject.Singleton")) {
            builder.singleton(true);
            builder.scope(Singleton.class);
        } else if (scope != null && !DEPENDENT.equals(scope.getName())) {
            // routed to the scope's own context — the application scope's, the request scope's, or the one an
            // extension registered — so that the instance lives and dies with the context rather than being
            // held as a raw singleton or created afresh for every asker
            builder.singleton(false);
            builder.scope(contextScopeOf(scope));
        } else {
            // the dependent default of section 2.10.5: created afresh for whoever asks
            builder.singleton(false);
        }
        // the bean types are exactly what was declared (or the API's {Object} default) — the implementation
        // class is not among them unless the extension said so
        builder.exposedTypes(record.classValues("types"));
        // the definition disposes of each instance it created as the instance is destroyed, which is the moment
        // the extension meant (section 2.10.5)
        builder.disposer((context, instance) ->
            dispose((BeanDefinition<SyntheticBeanDisposer<T>>) disposer, parameters, instance));
        beanContext.registerBeanDefinition(builder.build());
    }

    /**
     * The scope of the synthetic bean, which the compiler resolved: the one the extension set, or the one a
     * stereotype it named carries.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Annotation> scopeOf(AnnotationValue<CdiSyntheticBean> record) {
        Class<?> scope = record.classValue("scope").orElse(null);
        return scope == null || scope == void.class ? null : (Class<? extends Annotation>) scope;
    }

    /**
     * The scope annotation whose context holds the bean: the specification's built-in scopes are served by
     * this module's own contexts, and a scope an extension registered is served by the context it registered.
     */
    private static Class<? extends Annotation> contextScopeOf(Class<? extends Annotation> scope) {
        return switch (scope.getName()) {
            case "jakarta.enterprise.context.ApplicationScoped" ->
                io.micronaut.cdi.internal.metadata.CdiApplicationScope.class;
            case "jakarta.enterprise.context.RequestScoped" ->
                io.micronaut.cdi.internal.metadata.CdiRequestScope.class;
            default -> scope;
        };
    }

    /**
     * Disposes of an instance of a synthetic bean the way the extension said to (section 2.10.5).
     */
    private <T> void dispose(@Nullable BeanDefinition<SyntheticBeanDisposer<T>> disposerDefinition,
                             CdiParameters parameters, T instance) {
        try {
            if (disposerDefinition != null) {
                SyntheticBeanDisposer<T> disposer = beanContext.getBean(disposerDefinition);
                CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
                try {
                    disposer.dispose(instance, lookup, parameters);
                } finally {
                    // what the disposal function looked up as a dependent instance lives only as long as
                    // the disposal itself (section 2.10.5)
                    lookup.destroyTransients();
                }
            }
        } finally {
            CdiInstance<Object> creatorLookup = creatorLookups.remove(instance);
            if (creatorLookup != null) {
                // and what the creation function looked up belongs to the instance it created, gone with it
                creatorLookup.destroyTransients();
            }
        }
    }

    /**
     * The annotations a synthetic bean carries, so that the container can report what qualifies it and what
     * scope it is in the way it reports them for any other bean.
     */
    private static AnnotationMetadata metadataOf(AnnotationValue<CdiSyntheticBean> record,
                                                 List<AnnotationValue<Annotation>> qualifiers,
                                                 @Nullable String name,
                                                 @Nullable Class<? extends Annotation> scope) {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        for (AnnotationValue<Annotation> qualifier : qualifiers) {
            metadata.addDeclaredAnnotation(qualifier.getAnnotationName(), qualifier.getValues());
            metadata.addDeclaredStereotype(List.of(qualifier.getAnnotationName()),
                AnnotationUtil.QUALIFIER, Map.of());
        }
        if (name != null) {
            // a named bean has the name among its qualifiers (section 2.6), and is found by it
            metadata.addDeclaredAnnotation("jakarta.inject.Named", Map.of(AnnotationMetadata.VALUE_MEMBER, name));
            metadata.addDeclaredStereotype(List.of("jakarta.inject.Named"), AnnotationUtil.QUALIFIER, Map.of());
        }
        Set<String> alternativeStereotypes = new LinkedHashSet<>(List.of(record.stringValues("alternativeStereotypes")));
        for (String stereotype : record.stringValues("stereotypes")) {
            metadata.addDeclaredAnnotation(stereotype, Map.of());
            if (alternativeStereotypes.contains(stereotype)) {
                metadata.addDeclaredStereotype(List.of(stereotype), "jakarta.enterprise.inject.Alternative", Map.of());
            }
        }
        int[] priority = record.intValues("priority");
        if (priority.length > 0) {
            // the priority selects and ranks an alternative; it is carried as itself and as the order,
            // negated, the same way the compiler writes it for a selected alternative
            metadata.addDeclaredAnnotation("jakarta.annotation.Priority",
                Map.of(AnnotationMetadata.VALUE_MEMBER, priority[0]));
            metadata.addDeclaredAnnotation("io.micronaut.core.annotation.Order",
                Map.of(AnnotationMetadata.VALUE_MEMBER, -priority[0]));
            metadata.addDeclaredAnnotation("io.micronaut.context.annotation.Primary", Map.of());
        }
        // the effective scope, a stereotype-carried one included: the runtime reads this to know whether the bean
        // is dependent, and that it is a bean of the specification at all, which a dependent one is as much as any
        metadata.addDeclaredAnnotation(CdiScope.class.getName(), Map.of(
            AnnotationMetadata.VALUE_MEMBER, scope != null ? scope.getName() : "jakarta.enterprise.context.Dependent",
            "normal", record.booleanValue("normal").orElse(false)));
        if (record.booleanValue("alternative").orElse(false)) {
            metadata.addDeclaredAnnotation("jakarta.enterprise.inject.Alternative", Map.of());
        }
        return metadata;
    }

    /**
     * Creates a synthetic bean by asking the creator the extension named for it, which the container obtains
     * from the definition the compiler generated for the creator class. What the creator needs from the
     * container it asks the lookup it is handed for.
     */
    private <T> T create(Class<T> implementation,
                         @Nullable Class<? extends Annotation> scope,
                         BeanDefinition<SyntheticBeanCreator<T>> creatorDefinition,
                         CdiParameters parameters,
                         RuntimeBeanDefinition.CreationContext creationContext) {
        SyntheticBeanCreator<T> creator = beanContext.getBean(creatorDefinition);
        CdiInjectionPoint injectionPoint = currentInjectionPointOf(implementation, scope, creationContext);
        if (injectionPoint != null) {
            CurrentInjectionPoint.enter(injectionPoint);
        }
        CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
        boolean handedOver = false;
        try {
            T instance = creator.create(lookup, parameters);
            if (instance != null) {
                // what the creation function looked up as dependent instances belongs to the instance it
                // created, and is destroyed with it (section 2.10.5)
                creatorLookups.put(instance, lookup);
                handedOver = true;
            }
            return instance;
        } finally {
            if (!handedOver) {
                // a creation that failed, or made nothing, leaves nothing to hand the lookups to: what it
                // looked up is let go here rather than leaking
                lookup.destroyTransients();
            }
            if (injectionPoint != null) {
                CurrentInjectionPoint.leave();
            }
        }
    }

    /**
     * The injection point a dependent synthetic bean is being created for, as the creation under way reports it:
     * section 2.10.5 hands the creation function a lookup that can answer {@code InjectionPoint}.
     */
    private @Nullable CdiInjectionPoint currentInjectionPointOf(
        Class<?> implementation, @Nullable Class<? extends Annotation> scope,
        RuntimeBeanDefinition.CreationContext creationContext) {
        if (scope != null && !DEPENDENT.equals(scope.getName())) {
            // for anything but a dependent bean the specification leaves the answer open, and null it is
            return null;
        }
        // the injection point the bean is created for is the segment of the resolution that asked for it, and it is
        // absent for a lookup made directly rather than for an injection point
        if (!(creationContext.getInjectionPoint().orElse(null)
            instanceof BeanResolutionContext.Segment<?, ?> segment)) {
            return null;
        }
        Argument<?> argument = segment.getArgument();
        if (argument == null || !argument.getType().isAssignableFrom(implementation)) {
            return null;
        }
        CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
        return CdiInjectionPoint.of(container.canonicalBean(segment.getDeclaringType()), segment);
    }
}
