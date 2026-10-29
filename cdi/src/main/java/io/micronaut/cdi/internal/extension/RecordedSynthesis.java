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
import io.micronaut.cdi.internal.runtime.CdiInstance;
import io.micronaut.cdi.internal.runtime.CdiQualifiers;
import io.micronaut.cdi.internal.runtime.ObserverRegistry;
import io.micronaut.cdi.internal.runtime.OwnedDependents;
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

    public RecordedSynthesis(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    private java.util.Collection<BeanDefinition<Object>> recorded(Class<? extends java.lang.annotation.Annotation> record) {
        return beanContext.getBeanDefinitions(io.micronaut.inject.qualifiers.Qualifiers.byStereotype(record));
    }

    @PostConstruct
    void register() {
        // a record is identified by the extension that described it, so that a bean two compilations of one
        // application both recorded - each ran the extension - is one bean
        Map<String, BeanDefinition<?>> beans = new LinkedHashMap<>();
        Map<String, BeanDefinition<?>> disposers = new LinkedHashMap<>();
        Map<String, BeanDefinition<?>> observers = new LinkedHashMap<>();
        // each kind of record is found by its annotation on the references of the definitions the processor
        // registered for the classes the extensions named: only those definitions are loaded, rather than every
        // definition of the application
        for (BeanDefinition<?> definition : recorded(CdiSyntheticBean.class)) {
            definition.getAnnotationMetadata().findAnnotation(CdiSyntheticBean.class).ifPresent(record ->
                beans.putIfAbsent(record.stringValue("id").orElseThrow(), definition));
        }
        for (BeanDefinition<?> definition : recorded(CdiSyntheticDisposer.class)) {
            definition.getAnnotationMetadata().findAnnotation(CdiSyntheticDisposer.class).ifPresent(record ->
                disposers.putIfAbsent(record.stringValue().orElseThrow(), definition));
        }
        for (BeanDefinition<?> definition : recorded(CdiSyntheticObserver.class)) {
            definition.getAnnotationMetadata().findAnnotation(CdiSyntheticObserver.class).ifPresent(record ->
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
            CdiParameters parameters = new CdiParameters(beanContext,
                record.getAnnotations("params", CdiSyntheticParameter.class));
            invokers.addAll(parameters.invokers());
            BeanDefinition<?> disposer = disposers.get(bean.getKey());
            if (disposer == null && record.booleanValue("disposer").orElse(false)) {
                throw new IllegalStateException("The synthetic bean " + record.stringValue("implementation").orElse("")
                    + " was recorded with a disposer, and the definition of the disposer is not among the beans "
                    + "of the application: the definitions compiled with it are not on the classpath");
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
            invoker.validateLookups();
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
        Qualifier<T> qualifier = CdiQualifiers.declaredQualifierOf(qualifiers);
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
            builder.scope(ExtensionContexts.servedBy(scope));
        } else {
            // the dependent default of section 2.10.5: created afresh for whoever asks
            builder.singleton(false);
        }
        // the bean types are exactly what was declared (or the API's {Object} default) — the implementation
        // class is not among them unless the extension said so
        builder.exposedTypes(record.classValues("types"));
        if (disposer != null) {
            // the definition disposes of each instance it created as the instance is destroyed, which is the
            // moment the extension meant (section 2.10.5)
            builder.injectedDisposer((disposalContext, instance) ->
                dispose((BeanDefinition<SyntheticBeanDisposer<T>>) disposer, parameters, instance, disposalContext));
        }
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
     * Disposes of an instance of a synthetic bean the way the extension said to (section 2.10.5).
     */
    private <T> void dispose(BeanDefinition<SyntheticBeanDisposer<T>> disposerDefinition,
                             CdiParameters parameters, T instance,
                             RuntimeBeanDefinition.DisposalContext disposalContext) {
        SyntheticBeanDisposer<T> disposer = beanContext.getBean(disposerDefinition);
        // what the disposal function looks up as a dependent instance lives only as long as the disposal itself
        // (section 2.10.5): the resolver of the disposal destroys it as the disposer returns
        try (CdiInstance<Object> lookup = CdiInstance.ownedBy(beanContext, dependentsOf(disposalContext))) {
            disposer.dispose(instance, lookup, parameters);
        }
    }

    /**
     * The owner of what the lookup handed to a creation or a disposal function creates on behalf of the synthetic
     * bean: the dependent instances are destroyed with the created bean, or as the disposal returns.
     * Where the context tracks no dependents of a bean - a context other than Micronaut's own, whose creation
     * context hands out no resolver - the lookup owns them in a group of its own: the disposal closes it as it
     * returns, and the lookup a creation was handed is not closed, as Micronaut destroys nothing a bean it does not
     * track the dependents of looked up.
     */
    private OwnedDependents dependentsOf(RuntimeBeanDefinition.LookupContext lookupContext) {
        io.micronaut.context.BeanDependencyResolver resolver;
        try {
            resolver = lookupContext.getDependencies();
        } catch (UnsupportedOperationException e) {
            resolver = null;
        }
        return OwnedDependents.ownedBy(beanContext, resolver);
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
     *
     * <p>Section 2.10.5 hands the creation function a lookup that can answer {@code InjectionPoint}. For a dependent
     * bean created for an injection point, the lookup resolves the metadata within the creation under way
     * ({@link OwnedDependents#within}), so the metadata finds the point the bean is created for on the path of the
     * resolution, as it finds it for any bean injected there.</p>
     */
    private <T> T create(Class<T> implementation,
                         @Nullable Class<? extends Annotation> scope,
                         BeanDefinition<SyntheticBeanCreator<T>> creatorDefinition,
                         CdiParameters parameters,
                         RuntimeBeanDefinition.CreationContext creationContext) {
        SyntheticBeanCreator<T> creator = beanContext.getBean(creatorDefinition);
        // what the creation function looks up as dependent instances belongs to the instance it creates, and is
        // destroyed with it (section 2.10.5): the resolver of the creation creates it
        OwnedDependents dependents = dependentsOf(creationContext);
        CdiInstance<Object> lookup = CdiInstance.ownedBy(beanContext, dependents);
        if (isCreatedForAnInjectionPoint(implementation, scope, creationContext)) {
            return dependents.within(creationContext, () -> creator.create(lookup, parameters));
        }
        return creator.create(lookup, parameters);
    }

    /**
     * Whether a synthetic bean is being created for an injection point, which is what its injection point metadata
     * then describes: the creation under way reports the segment of the resolution that asked for it - the lookup's
     * own for a bean a lookup creates - and none for a bean asked of Micronaut directly.
     */
    private static boolean isCreatedForAnInjectionPoint(Class<?> implementation,
                                                        @Nullable Class<? extends Annotation> scope,
                                                        RuntimeBeanDefinition.CreationContext creationContext) {
        if (scope != null && !DEPENDENT.equals(scope.getName())) {
            // for anything but a dependent bean the specification leaves the answer open, and null it is
            return false;
        }
        if (!(creationContext.getInjectionPoint().orElse(null)
            instanceof BeanResolutionContext.Segment<?, ?> segment)) {
            return false;
        }
        Argument<?> argument = segment.getArgument();
        return argument != null && argument.getType().isAssignableFrom(implementation);
    }
}
