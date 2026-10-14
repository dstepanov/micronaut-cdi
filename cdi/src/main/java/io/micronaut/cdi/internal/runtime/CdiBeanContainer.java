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

import io.micronaut.cdi.internal.metadata.CdiUnproxyable;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.cdi.spi.ExpressionLanguageBridge;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The container itself, as a program reaches it: the object of section 2.9.1, through which a bean can be looked
 * up, a scope asked about and a reference obtained.
 *
 * <p>It is a reading of the Micronaut bean context rather than a container of its own. Every question it answers
 * is one the bean context can answer, asked in the terms of the specification: a lookup by a type and a set of
 * qualifier annotations is a lookup by an argument and a Micronaut qualifier, and a bean is a
 * {@link CdiBean} over the bean definition that was compiled for it.</p>
 *
 * <p>What CDI Lite asks for is the {@code BeanContainer}; the {@code BeanManager} of CDI Full extends it, and is
 * implemented here as far as CDI Lite can answer it. That is worth having even in a module that implements only
 * Lite: a program written against the specification reaches for the manager, and the parts of it that are about
 * Lite are ones this container knows. Everything on it that belongs to CDI Full — decorators, passivation,
 * portable extensions, the expression language, and building a bean out of an annotated type — says so rather
 * than answering with an empty result that would read as there being none.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@io.micronaut.context.annotation.Context
@io.micronaut.cdi.internal.metadata.CdiBuiltIn
@io.micronaut.cdi.internal.metadata.CdiScope("jakarta.enterprise.context.Dependent")
@jakarta.enterprise.inject.Default
@Internal
public final class CdiBeanContainer implements BeanManager, io.micronaut.cdi.MicronautBeanContainer {

    /**
     * The annotations the specification declares, which are what they are whatever a build recorded.
     */
    private static final Set<String> NORMAL_SCOPES = Set.of(
        "jakarta.enterprise.context.ApplicationScoped", "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped", "jakarta.enterprise.context.ConversationScoped");
    private static final Set<String> PSEUDO_SCOPES = Set.of(
        "jakarta.enterprise.context.Dependent", "jakarta.inject.Singleton");
    /**
     * The number of injection points what they resolve to is kept for.
     */
    private static final int MAX_SELECTIONS = 16_384;

    private final BeanContext beanContext;
    private final RequestScope requestScope;
    private final io.micronaut.cdi.internal.context.ApplicationScope applicationScope;
    private volatile @Nullable List<CdiBean<?>> beans;
    /**
     * Each bean of {@link #beans} by itself, for finding the container's own bean of a definition.
     */
    private volatile @Nullable Map<CdiBean<?>, CdiBean<?>> canonical;
    /**
     * The beans narrowed by the class of a bean type, for each class a lookup has asked for: emptied whenever
     * the beans are read again.
     */
    private volatile Map<Class<?>, List<CdiBean<?>>> byClass = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The beans less the replaced ones ({@link #beans}), with the beans they were narrowed from.
     */
    private volatile @Nullable Deployed deployed;
    /**
     * What each injection point resolves to ({@link #selectionFor}), by the identity of the point: the arguments of a
     * compiled definition are constants, and two points of one type and name may declare different qualifiers.
     * Emptied whenever the beans are read again.
     */
    private volatile Map<PointKey, Selection> selections = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile @Nullable RecordedTypeIndex typeIndex;
    private volatile @Nullable CompiledAnnotationTypes types;
    private boolean validated;
    private final ObserverRegistry observers;
    /**
     * The contexts of the built-in scopes, which hold no state of their own beyond the scope they read: made once
     * rather than for each event an observer of a bean in one of them is notified of.
     */
    private final List<Context> requestContexts;
    private final List<Context> applicationContexts;
    private final List<Context> singletonContexts;
    private final List<Context> dependentContexts;
    /**
     * The contexts extensions registered, a bean of the context created as it starts: looked up once.
     */
    private volatile java.util.@Nullable Optional<io.micronaut.cdi.internal.extension.ExtensionContexts>
        extensionContexts;

    @jakarta.inject.Inject
    public CdiBeanContainer(BeanContext beanContext, RequestScope requestScope,
                            io.micronaut.cdi.internal.context.ApplicationScope applicationScope, ObserverRegistry observers) {
        this.beanContext = beanContext;
        this.requestScope = requestScope;
        this.applicationScope = applicationScope;
        this.observers = observers;
        this.requestContexts = List.of(
            CdiContext.ofRequest(jakarta.enterprise.context.RequestScoped.class, requestScope));
        this.applicationContexts = List.of(
            CdiContext.ofApplication(jakarta.enterprise.context.ApplicationScoped.class, applicationScope));
        this.singletonContexts = List.of(CdiContext.ofSingleton(Singleton.class, beanContext));
        this.dependentContexts = List.of(CdiContext.holdingNothing(Dependent.class));
        // the container of a running application is what CDI.current() resolves to, and there is nothing else
        // for it to resolve through: the specification's own entry point is static. It is registered as the
        // container starts, which is what makes this bean one Micronaut creates eagerly rather than on demand
        CdiRunning.started(this);
    }

    @PreDestroy
    void stopped() {
        CdiRunning.stopped(this);
    }

    @Override
    public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
        boolean amongTheTypes = false;
        Argument<?> required = SpecificationTypes.argumentOf(beanType);
        List<Argument<?>> types = bean instanceof CdiBean<?> own ? own.types()
            : SpecificationTypes.argumentsOf(bean.getTypes());
        for (Argument<?> type : types) {
            if (type.equalsStructure(required)) {
                amongTheTypes = true;
                break;
            }
            // a raw request — a plain class, a primitive — is satisfied by the bean type it erases to, but a
            // parameterized request matches only exactly: a List<String> bean is not a List<Integer>
            if (CdiTypes.isClass(required)) {
                Class<?> beanRaw = CdiTypes.rawClassOf(type);
                if (beanRaw != null && CdiTypes.boxedOf(beanRaw) == CdiTypes.boxedOf(required.getType())) {
                    amongTheTypes = true;
                    break;
                }
            }
        }
        if (!amongTheTypes && CdiTypes.isParameterized(required)) {
            // a bean type with variables among its arguments - a producer of List<T> - is a List<Spider> as well,
            // by the rules the bean was resolved with
            amongTheTypes = CdiAssignability.isTypeMatching(typeIndex(), types, required);
        }
        if (!amongTheTypes) {
            throw new IllegalArgumentException("The type " + beanType.getTypeName()
                + " is not among the bean types of " + bean);
        }
        if (bean instanceof CdiBean<?> cdiBean) {
            String unproxyable = cdiBean.definition().getAnnotationMetadata()
                .stringValue(CdiUnproxyable.class).orElse(null);
            if (unproxyable != null) {
                // section 3.11: a contextual reference to a bean in a normal scope is its client proxy, and
                // this bean cannot have one
                throw new jakarta.enterprise.inject.UnproxyableResolutionException(unproxyable);
            }
            // the contextual reference to a bean in a normal scope is the client proxy, where creating the
            // bean — the Contextual contract — is creating the instance the proxy reaches for
            Object proxy = cdiBean.proxyReference();
            if (proxy != null) {
                return proxy;
            }
        }
        return create(bean, ctx);
    }

    @SuppressWarnings("unchecked")
    private static <T> T create(Bean<T> bean, CreationalContext<?> ctx) {
        if (bean instanceof CdiBean<T> cdiBean && !cdiBean.isDependent()) {
            // the contextual reference of a bean that has a scope is the instance of the scope
            return cdiBean.scopedInstance();
        }
        return bean.create((CreationalContext<T>) ctx);
    }

    @Override
    public <T> CreationalContext<T> createCreationalContext(@Nullable Contextual<T> contextual) {
        return new CdiCreationalContext<>(beanContext);
    }

    @Override
    public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
        return beansOf(beanType, wellFormed(CdiQualifier.ofInstances(types(), qualifiers)));
    }

    @Override
    public Set<Bean<?>> getBeans(Argument<?> beanType, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                 io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length + 1);
        all.add(CdiQualifier.ofValue(types(), qualifier));
        all.addAll(qualifiersOf(qualifiers));
        return beansOf((Type) beanType, wellFormed(all));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Set<Bean<?>> getBeans(Argument<?> beanType, io.micronaut.context.Qualifier<?> qualifier) {
        // the qualifier is handed every eligible definition at once and the type that was asked for, as Micronaut's
        // own lookups hand it: a qualifier may choose among the candidates - the closest type arguments - rather
        // than test each on its own
        Map<BeanDefinition<?>, Bean<?>> byDefinition = new java.util.LinkedHashMap<>();
        for (Bean<?> bean : beansOf(beanType, List.of(CdiQualifier.ANY))) {
            byDefinition.putIfAbsent(((CdiBean<?>) bean).definition(), bean);
        }
        Collection kept = ((io.micronaut.context.Qualifier) qualifier)
            .filter(beanType.getType(), (Collection) byDefinition.keySet());
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (Object definition : kept) {
            Bean<?> bean = byDefinition.get(definition);
            if (bean != null) {
                beans.add(bean);
            }
        }
        return beans;
    }

    /**
     * The beans of a type as the specification's API hands it over, read into Micronaut's form of it first.
     *
     * @param required           The required type
     * @param requiredQualifiers The required qualifiers
     * @return The beans
     * @throws IllegalArgumentException For a type that is not a class, a parameterized type, a wildcard, a type
     *                                  variable or an array of one
     */
    Set<Bean<?>> beansOf(Type required, List<CdiQualifier> requiredQualifiers) {
        // Micronaut's own form of a type is one of the types a lookup may be made by
        return beansOf(SpecificationTypes.argumentOf(required), requiredQualifiers);
    }

    /**
     * What an injection point of a bean of the specification resolves to: a bean's own - a field, a parameter of
     * its constructor, of an initializer or of a producer - an observer's or a disposer's, which the processor
     * marks for this container to resolve ({@code ResolveWith}). The beans it is resolved among are the beans of
     * {@link #beansOf} for the full declared type of the point and the qualifiers it declares, and one is chosen
     * among them as {@link #resolve} chooses: section 5.2 resolves among the beans of the specification, and a
     * bean of Micronaut's own of the same type is no more than one Micronaut also has.
     *
     * <p>Where none of those beans is a bean of the specification - none is of the class of the point, or none of
     * the ones that are qualifies - the point is left to Micronaut's rules among the beans of Micronaut's own
     * ({@link Selection#MICRONAUT}, resolved by {@link Selection#micronautQualifier}): a bean of the specification
     * the qualifiers of the point rejected is not admitted again by Micronaut's, by the prefix of its name or by
     * being primary. So is a type with a type variable among its arguments, which no bean type is.</p>
     *
     * @param point The injection point
     * @return What it resolves to
     * @throws AmbiguousResolutionException Where more than one bean remains
     */
    Selection selectionFor(Argument<?> point) {
        Map<PointKey, Selection> cache = selections;
        PointKey key = new PointKey(point);
        Selection known = cache.get(key);
        if (known != null) {
            return known;
        }
        Selection selection = select(point);
        if (cache.size() < MAX_SELECTIONS) {
            // a point made anew for each injection - one Micronaut wraps to evaluate an expression - is not kept
            // beyond a bound
            cache.putIfAbsent(key, selection);
        }
        return selection;
    }

    private Selection select(Argument<?> point) {
        boolean specification = false;
        boolean micronaut = false;
        for (CdiBean<?> bean : candidatesOfType(point)) {
            if (isInterceptor(bean)) {
                // a bean of the specification of its class, though never one a point is injected with: it says
                // nothing of which rules the point is resolved by
                continue;
            }
            if (CdiResolution.isBeanOfTheSpecification(bean.definition())) {
                specification = true;
            } else {
                micronaut = true;
            }
            if (specification && micronaut) {
                break;
            }
        }
        if (!specification) {
            return Selection.MICRONAUT;
        }
        Set<Bean<?>> eligible;
        try {
            eligible = beansOf(point, CdiQualifier.declared(point.getAnnotationMetadata()));
        } catch (IllegalArgumentException e) {
            // a type with a variable the declaring class gives a value the definition does not carry
            return Selection.MICRONAUT;
        }
        if (micronaut && ofTheSpecification(eligible).isEmpty()) {
            // the beans of the specification of the class are not among the ones the qualifiers admit, and the
            // beans of Micronaut's own of it are left to Micronaut's rules
            return Selection.MICRONAUT;
        }
        Bean<?> selected = resolve(eligible);
        return selected == null ? Selection.NONE : new Selection(((CdiBean<?>) selected).definition());
    }

    /**
     * The definitions a lookup of the type with the qualifiers resolves among, as {@link #beansOf} has them: what
     * an {@code Instance} enumerates, narrowed further by the Micronaut qualifier a program narrowed it by.
     *
     * @param type       The type looked up
     * @param required   The qualifiers of the specification required
     * @param narrowedBy The Micronaut qualifier, or {@code null}
     * @param <T>        The bean type
     * @return The definitions, the alternatives not yet narrowed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> List<BeanDefinition<T>> definitionsOf(Argument<T> type, List<CdiQualifier> required,
                                              io.micronaut.context.@Nullable Qualifier<?> narrowedBy) {
        List<BeanDefinition<T>> definitions = new ArrayList<>();
        for (Bean<?> bean : beansOf(type, required)) {
            BeanDefinition<T> definition = (BeanDefinition<T>) ((CdiBean<?>) bean).definition();
            if (!definitions.contains(definition)) {
                definitions.add(definition);
            }
        }
        if (narrowedBy == null || definitions.isEmpty()) {
            return definitions;
        }
        // the qualifier is handed every eligible definition at once, as Micronaut's own lookups hand it
        Collection kept = ((io.micronaut.context.Qualifier) narrowedBy).filter(type.getType(), (Collection) definitions);
        return kept.size() == definitions.size() ? definitions : new ArrayList<>((Collection<BeanDefinition<T>>) kept);
    }

    /**
     * The beans of a type with the qualifiers, by the rules of section 2.4.2, before the alternatives have their
     * say: the one candidate selection of the container, which an injection point, an {@code Instance}, the bean
     * manager and the validation of the deployment all make. A bean qualifies by the bean types its definition
     * recorded - a type Micronaut only indexes the definition under is none of them - and by the qualifiers the
     * container records of it, with what a portable extension added; a bean another one replaces with Micronaut's
     * {@code Replaces} is left out, as Micronaut leaves it out, and so is an interceptor, which is never injected.
     *
     * @param beanType The required type
     * @param required The required qualifiers, none at all requiring the default qualifier
     * @return The beans
     * @throws IllegalArgumentException For a type with a type variable among its arguments
     */
    Set<Bean<?>> beansOf(Argument<?> beanType, List<CdiQualifier> required) {
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (CdiBean<?> bean : beansOfType(beanType)) {
            if (bean.definition() instanceof CdiInjectionPointFactory<?>
                || CdiAssignability.areQualifiersMatching(bean.qualifiers(), required)) {
                beans.add(bean);
            }
        }
        return beans;
    }

    /**
     * The beans one of whose bean types matches the required one, less the ones another among them replaces.
     */
    private Set<CdiBean<?>> beansOfType(Argument<?> beanType) {
        Set<CdiBean<?>> beans = new LinkedHashSet<>();
        for (CdiBean<?> bean : candidatesOfType(beanType)) {
            if (bean.definition() instanceof CdiInjectionPointFactory<?> builtIn) {
                // the built-in event and lookup exist for whatever legal type an injection point asks them
                // for, so the one bean of each answers every parameterization of its type — and the raw type
                // itself
                Class<?> raw = CdiTypes.classOf(beanType);
                if (raw != null && builtIn.isBeanType(raw)) {
                    beans.add(bean);
                }
                continue;
            }
            if (!isInterceptor(bean)
                && CdiAssignability.isTypeMatching(typeIndex(), bean.types(), requireNoTypeVariable(beanType))) {
                beans.add(bean);
            }
        }
        return withoutReplaced(beans);
    }

    private static boolean isInterceptor(CdiBean<?> bean) {
        return bean.definition().getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor");
    }

    private static Set<Bean<?>> ofTheSpecification(Set<Bean<?>> beans) {
        Set<Bean<?>> kept = new LinkedHashSet<>();
        for (Bean<?> bean : beans) {
            if (bean instanceof CdiBean<?> cdiBean && CdiResolution.isBeanOfTheSpecification(cdiBean.definition())) {
                kept.add(bean);
            }
        }
        return kept;
    }

    /**
     * The beans less the ones another among them replaces with Micronaut's {@code Replaces}, which Micronaut
     * leaves out of the candidates of a lookup and an injection point alike.
     */
    private static Set<CdiBean<?>> withoutReplaced(Set<CdiBean<?>> beans) {
        if (beans.size() < 2) {
            return beans;
        }
        Map<BeanDefinition<?>, CdiBean<?>> byDefinition = new java.util.LinkedHashMap<>();
        for (CdiBean<?> bean : beans) {
            byDefinition.put(bean.definition(), bean);
        }
        java.util.Collection<BeanDefinition<?>> kept = CdiResolution.withoutReplaced(byDefinition.keySet());
        if (kept == byDefinition.keySet()) {
            return beans;
        }
        Set<CdiBean<?>> remaining = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : kept) {
            remaining.add(byDefinition.get(definition));
        }
        return remaining;
    }

    /**
     * The beans that may have a bean type matching the given one, in the order of {@link #candidates()}: a bean
     * type matches a class or a parameterized type only where its own class is the same, a primitive and its
     * wrapper taken as one, so the beans are narrowed by that class before the rules of the specification
     * decide. The built-in event and lookup are kept where they answer the class. A type that names no class,
     * an array, and {@code Object}, which every bean has, are compared against every bean.
     */
    private List<CdiBean<?>> candidatesOfType(Argument<?> beanType) {
        List<CdiBean<?>> all = candidates();
        Class<?> raw = CdiTypes.classOf(beanType);
        if (raw == null || raw == Object.class) {
            return all;
        }
        Class<?> key = CdiTypes.boxedOf(raw);
        Map<Class<?>, List<CdiBean<?>>> index = byClass;
        List<CdiBean<?>> narrowed = index.get(key);
        if (narrowed == null) {
            List<CdiBean<?>> found = new java.util.ArrayList<>();
            for (CdiBean<?> bean : all) {
                if (bean.definition() instanceof CdiInjectionPointFactory<?> builtIn
                    ? builtIn.isBeanType(raw) : hasTypeOfClass(bean, key)) {
                    found.add(bean);
                }
            }
            narrowed = List.copyOf(found);
            if (beans == all) {
                // kept only while the beans it was narrowed from are the current ones
                index.putIfAbsent(key, narrowed);
            }
        }
        return narrowed;
    }

    private static boolean hasTypeOfClass(CdiBean<?> bean, Class<?> boxedClass) {
        for (Argument<?> type : bean.types()) {
            Class<?> raw = CdiTypes.classOf(type);
            if (raw != null && CdiTypes.boxedOf(raw) == boxedClass) {
                return true;
            }
        }
        return false;
    }

    private static Argument<?> requireNoTypeVariable(Argument<?> required) {
        CdiAssignability.requireNoTypeVariable(required);
        return required;
    }

    private List<CdiQualifier> qualifiersOf(io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length);
        for (io.micronaut.core.annotation.AnnotationValue<?> qualifier : qualifiers) {
            all.add(CdiQualifier.ofValue(types(), qualifier));
        }
        return all;
    }

    /**
     * What the specification requires of the qualifiers a lookup names: each is a qualifier, and no qualifier
     * type is named twice unless it is repeatable.
     */
    private List<CdiQualifier> wellFormed(List<CdiQualifier> qualifiers) {
        CdiQualifier.requireWellFormed(types(), List.of(), qualifiers);
        return qualifiers;
    }

    /**
     * The generic hierarchies of the classes the application was compiled with, which types are compared by.
     */
    private RecordedTypeIndex typeIndex() {
        RecordedTypeIndex index = typeIndex;
        if (index == null) {
            // a bean of the context, the same for as long as it runs: looked up once rather than for each type compared
            index = RecordedTypeIndex.of(beanContext);
            typeIndex = index;
        }
        return index;
    }

    /**
     * What the application was compiled with of each qualifier and interceptor binding type.
     */
    private CompiledAnnotationTypes types() {
        CompiledAnnotationTypes compiled = types;
        if (compiled == null) {
            compiled = CompiledAnnotationTypes.of(beanContext);
            types = compiled;
        }
        return compiled;
    }

    /**
     * The container's own bean for the given definition, so that everyone who describes the same bean holds
     * the same object: the one {@code getBeans} answers with.
     *
     * @param definition The definition
     * @return The bean
     */
    public CdiBean<?> canonicalBean(io.micronaut.inject.BeanDefinition<?> definition) {
        CdiBean<?> described = new CdiBean<>(beanContext, definition);
        List<CdiBean<?>> all = candidates();
        Map<CdiBean<?>, CdiBean<?>> index = canonical;
        if (index != null) {
            CdiBean<?> found = index.get(described);
            return found != null ? found : described;
        }
        for (CdiBean<?> candidate : all) {
            if (candidate.equals(described)) {
                return candidate;
            }
        }
        return described;
    }

    /**
     * Forgets the beans already read, so that beans registered while the container starts — the synthetic
     * ones of section 2.10.5 — are seen by the next lookup.
     */
    public void refreshCandidates() {
        synchronized (this) {
            beans = null;
            canonical = null;
            byClass = new java.util.concurrent.ConcurrentHashMap<>();
            selections = new java.util.concurrent.ConcurrentHashMap<>();
        }
    }

    /**
     * Validates the deployment, once, the way {@link DeploymentValidation} has it. It is run as the container
     * starts, after the portable extensions have added what they add and before they are told the deployment
     * validated.
     *
     * @throws jakarta.enterprise.inject.spi.DeploymentException Where the deployment is invalid
     */
    public void validateDeployment() {
        synchronized (this) {
            if (validated) {
                return;
            }
            validated = true;
        }
        new DeploymentValidation(this).validate();
    }

    /**
     * The beans of the deployment: the beans the container knows, less the ones another among them replaces with
     * Micronaut's {@code Replaces}, which are no beans of it - not of their name, and not with dependencies to
     * validate - as they are none of their types.
     *
     * @return The beans
     */
    List<CdiBean<?>> beans() {
        List<CdiBean<?>> all = candidates();
        Deployed known = deployed;
        if (known == null || known.from() != all) {
            known = new Deployed(all, List.copyOf(withoutReplaced(new LinkedHashSet<>(all))));
            deployed = known;
        }
        return known.beans();
    }

    /**
     * Every bean of the container, read once from the compiled definitions.
     *
     * <p>The programmatic lookup of section 2.4.2 asks for the beans a type and some qualifiers make eligible,
     * before anything is resolved among them: a selected alternative and the bean it outranks are both in the
     * answer, and choosing between them is what {@link #resolve} is for. Micronaut's own lookups resolve as
     * they go, so this reads the definition references directly — everything that was compiled, minus what is
     * disabled and minus what an environment narrowed away — and lets the rules of the specification decide
     * eligibility.</p>
     */
    private List<CdiBean<?>> candidates() {
        List<CdiBean<?>> resolved = beans;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (beans == null) {
                List<CdiBean<?>> loaded = loadCandidates();
                Map<CdiBean<?>, CdiBean<?>> byItself = new java.util.HashMap<>();
                for (CdiBean<?> bean : loaded) {
                    byItself.putIfAbsent(bean, bean);
                }
                canonical = byItself;
                beans = loaded;
            }
            return beans;
        }
    }

    /**
     * The definition of a reference where it is a bean here: one whose condition does not hold is not, as Micronaut
     * has it, and neither is one that names a class absent from the classpath - an optional dependency the
     * application does not have - which Micronaut reports as a definition that failed to initialize. Anything else
     * that goes wrong reading a definition, a condition that throws among it, is a broken deployment, and is
     * reported rather than taken for there being no bean.
     */
    private @Nullable BeanDefinition<?> enabledDefinitionOf(io.micronaut.inject.BeanDefinitionReference<Object> reference) {
        try {
            BeanDefinition<?> definition = reference.load(beanContext);
            return definition.isEnabled(beanContext) ? definition : null;
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            return null;
        } catch (io.micronaut.context.exceptions.BeanInstantiationException e) {
            if (CdiResolution.isAbsentClass(e)) {
                return null;
            }
            throw e;
        }
    }

    private List<CdiBean<?>> loadCandidates() {
        // the references are the ones the beans predicate of the context admits, so an environment that
        // narrowed the context reports exactly the beans it narrowed it to
        // a compiled definition is equal to every other of its class, and a runtime one only to itself, so the set
        // holds each bean once
        java.util.Set<BeanDefinition<?>> definitions = new LinkedHashSet<>();
        for (io.micronaut.inject.BeanDefinitionReference<Object> reference
            : beanContext.getBeanDefinitionReferences()) {
            BeanDefinition<?> definition = enabledDefinitionOf(reference);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        // a bean the container was handed at runtime — a synthetic bean an extension described — has a
        // definition without a compiled reference
        definitions.addAll(beanContext.getAllBeanDefinitions());
        List<CdiBean<?>> loaded = new java.util.ArrayList<>();
        // an abstract class is not a bean of the specification, however it is annotated, and a bean in a normal
        // scope is two definitions, the proxy and its target, of which the proxy is the bean
        for (BeanDefinition<?> definition : CdiResolution.beansAmong(definitions)) {
            if (io.micronaut.cdi.internal.extension.ExtensionComponents.isComponent(definition.getAnnotationMetadata())) {
                // the definition of a creator, a disposer, a synthetic observer or a context an extension
                // named: what the container instantiates the class through, and not a bean of the application
                continue;
            }
            loaded.add(new CdiBean<>(beanContext, definition));
        }
        return List.copyOf(loaded);
    }

    @Override
    public Set<Bean<?>> getBeans(String name) {
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (CdiBean<?> bean : beans()) {
            if (name.equals(bean.getName())) {
                beans.add(bean);
            }
        }
        return beans;
    }

    @Override
    public <X> @Nullable Bean<? extends X> resolve(@Nullable Set<Bean<? extends X>> beans) {
        if (beans == null || beans.isEmpty()) {
            return null;
        }
        if (beans.size() == 1) {
            return beans.iterator().next();
        }
        // more than one bean qualifies: a bean of the specification outranks a bean of Micronaut's own of the
        // same type, as at an injection point - among beans of Micronaut's own alone Micronaut's precedence chooses - the
        // alternatives outrank everything else, and among them the one of the highest priority wins. Anything short
        // of a single winner is the ambiguity of section 2.4.2
        List<Bean<? extends X>> narrowed = CdiResolution.narrow(
            CdiResolution.resolvedAmong(beans, CdiBeanContainer::definitionOf, null, beanContext),
            Bean::isAlternative, CdiBeanContainer::priorityOf);
        if (narrowed.size() == 1) {
            return narrowed.get(0);
        }
        throw new AmbiguousResolutionException("More than one bean qualifies: " + beans);
    }

    private static @Nullable BeanDefinition<?> definitionOf(Bean<?> bean) {
        // a bean the container did not describe - one a program handed in - has none, and is taken as one of the
        // specification
        return bean instanceof CdiBean<?> cdiBean ? cdiBean.definition() : null;
    }

    private static int priorityOf(Bean<?> bean) {
        if (bean instanceof CdiBean<?> cdiBean) {
            return CdiResolution.priorityOf(cdiBean.definition());
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, Annotation... qualifiers) {
        return resolveObserverMethods(event, wellFormed(CdiQualifier.ofInstances(types(), qualifiers)));
    }

    @Override
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                                                     io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length + 1);
        all.add(CdiQualifier.ofValue(types(), qualifier));
        all.addAll(qualifiersOf(qualifiers));
        return resolveObserverMethods(event, wellFormed(all));
    }

    @SuppressWarnings("unchecked")
    private <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, List<CdiQualifier> qualifiers) {
        if (CdiTypes.declaresTypeVariables(typeIndex(), event.getClass())) {
            throw new IllegalArgumentException("The event object's class " + event.getClass().getName()
                + " declares type variables, and its runtime class alone does not resolve them");
        }
        Set<ObserverMethod<? super T>> resolved = new LinkedHashSet<>();
        for (ObserverMethod<?> observer : observers.resolve(Argument.of(event.getClass()), qualifiers, false)) {
            resolved.add((ObserverMethod<? super T>) observer);
        }
        // the resolution the specification describes covers observers of both notifications
        for (ObserverMethod<?> observer : observers.resolve(Argument.of(event.getClass()), qualifiers, true)) {
            resolved.add((ObserverMethod<? super T>) observer);
        }
        return resolved;
    }

    @Override
    public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
        if (interceptorBindings.length == 0) {
            throw new IllegalArgumentException("An interceptor is resolved by at least one interceptor binding");
        }
        Set<Class<? extends Annotation>> distinct = new java.util.HashSet<>();
        for (Annotation binding : interceptorBindings) {
            if (!isInterceptorBinding(binding.annotationType())) {
                throw new IllegalArgumentException("The annotation " + binding.annotationType().getName()
                    + " is not an interceptor binding");
            }
            // a repeatable binding may be given once for each of its occurrences
            if (!distinct.add(binding.annotationType())
                && !Boolean.TRUE.equals(types().isRepeatable(binding.annotationType().getName()))) {
                throw new IllegalArgumentException("The interceptor binding " + binding.annotationType().getName()
                    + " was given twice");
            }
        }
        // compared as the interceptor chains compare them, so that what is reported here is what runs
        io.micronaut.interceptor.spi.InterceptorBindings given = io.micronaut.interceptor.spi.InterceptorBindings
            .ofValues(bindingValuesOf(CdiQualifier.ofInstances(types(), interceptorBindings)));
        // selected as the interceptors implementation selects the chain of an element bound by these bindings: the
        // enabled interceptor classes bound to them, by priority, then by class name, with the ones the SE bootstrap
        // listed after them in the order of the list (CdiInterceptorEnablement), so that what is reported here is
        // what is invoked
        List<Interceptor<?>> resolved = new java.util.ArrayList<>();
        for (BeanDefinition<?> definition : io.micronaut.interceptor.spi.InterceptorBindings
            .boundInterceptors(beanContext, given)) {
            CdiInterceptor<?> interceptor = new CdiInterceptor<>(beanContext, definition);
            // a chain holds the interceptor classes whatever they interpose on; the question is asked for one kind
            if (interceptor.intercepts(type)) {
                resolved.add(interceptor);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * The values of interceptor bindings handed over as annotation instances, in the form the Jakarta Interceptors
     * implementation compares bindings in. They are the values the bindings were read into: a member that holds an
     * annotation holds its value already, as compiled metadata records it and as the reflection module reads an
     * instance, so nothing is read again here - core's own reading of an annotation instance would reflect.
     */
    private static List<AnnotationValue<?>> bindingValuesOf(List<CdiQualifier> bindings) {
        List<AnnotationValue<?>> values = new java.util.ArrayList<>(bindings.size());
        for (CdiQualifier binding : bindings) {
            values.add(binding.written());
        }
        return values;
    }

    /**
     * What answers a question about an annotation class the build recorded nothing of, which only reading the
     * class can answer.
     */
    private CdiReflection reflection(String what, Class<? extends Annotation> annotationType) {
        return CdiReflection.require(beanContext, what + " of " + annotationType.getName()
            + ", an annotation the application was not compiled with as one,");
    }

    @Override
    public boolean isScope(Class<? extends Annotation> annotationType) {
        String name = annotationType.getName();
        if (NORMAL_SCOPES.contains(name) || PSEUDO_SCOPES.contains(name) || isRegisteredScope(annotationType)) {
            return true;
        }
        CdiReflection reflection = reflection("BeanContainer.isScope", annotationType);
        return reflection.isAnnotated(annotationType, jakarta.inject.Scope.class)
            || reflection.isAnnotated(annotationType, NormalScope.class);
    }

    @Override
    public boolean isNormalScope(Class<? extends Annotation> annotationType) {
        String name = annotationType.getName();
        if (NORMAL_SCOPES.contains(name)) {
            return true;
        }
        if (PSEUDO_SCOPES.contains(name)) {
            return false;
        }
        java.util.Optional<io.micronaut.cdi.internal.extension.ExtensionContexts> extensionContexts =
            beanContext.findBean(io.micronaut.cdi.internal.extension.ExtensionContexts.class);
        if (extensionContexts.isPresent() && !extensionContexts.get().contextsFor(annotationType).isEmpty()) {
            // a scope an extension registered is normal where the extension registered it as one
            return extensionContexts.get().isNormal(annotationType);
        }
        return reflection("BeanContainer.isNormalScope", annotationType).isAnnotated(annotationType, NormalScope.class);
    }

    private boolean isRegisteredScope(Class<? extends Annotation> annotationType) {
        return beanContext.findBean(io.micronaut.cdi.internal.extension.ExtensionContexts.class)
            .map(contexts -> !contexts.contextsFor(annotationType).isEmpty())
            .orElse(false);
    }

    @Override
    public boolean isQualifier(Class<? extends Annotation> annotationType) {
        // the qualifiers of the specification, and the ones the beans of the application were compiled with —
        // an annotation the discovery phase registered as a qualifier among them, though nothing on it says so
        Boolean known = CdiQualifier.isKnownQualifier(types(), annotationType.getName());
        if (known != null) {
            return known;
        }
        return reflection("BeanContainer.isQualifier", annotationType)
            .isAnnotated(annotationType, jakarta.inject.Qualifier.class);
    }

    @Override
    public boolean isStereotype(Class<? extends Annotation> annotationType) {
        if ("jakarta.enterprise.inject.Model".equals(annotationType.getName())) {
            return true;
        }
        return reflection("BeanContainer.isStereotype", annotationType)
            .isAnnotated(annotationType, jakarta.enterprise.inject.Stereotype.class);
    }

    @Override
    public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
        Boolean recorded = types().isInterceptorBinding(annotationType.getName());
        if (recorded != null) {
            return recorded;
        }
        return reflection("BeanContainer.isInterceptorBinding", annotationType)
            .isAnnotated(annotationType, jakarta.interceptor.InterceptorBinding.class);
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        Context active = activeContext(scopeType);
        if (active != null) {
            return active;
        }
        throw new jakarta.enterprise.context.ContextNotActiveException("No context of "
            + scopeType.getName() + " is active on the current thread");
    }

    /**
     * The active context of the scope, as {@link #getContext(Class)} finds it, or {@code null} where none is
     * active: for a check made on every event an observer of a bean in a normal scope is notified of, which an
     * exception would make the cost of.
     *
     * @param scopeType The scope
     * @return The active context, or {@code null}
     * @throws IllegalArgumentException where no context of the scope is registered
     */
    @Nullable Context activeContext(Class<? extends Annotation> scopeType) {
        Collection<Context> contexts = getContexts(scopeType);
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("There is no context for the scope " + scopeType.getName());
        }
        Context active = null;
        for (Context context : contexts) {
            if (context.isActive()) {
                if (active != null) {
                    throw new IllegalStateException("More than one context of " + scopeType.getName()
                        + " is active on the current thread");
                }
                active = context;
            }
        }
        return active;
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        if (scopeType == Singleton.class) {
            return singletonContexts;
        }
        if (scopeType == Dependent.class) {
            return dependentContexts;
        }
        // a scope an extension registered a context for (section 2.10.1). A context a portable extension added
        // for the request or the application scope takes the place of the container's own: the beans of the
        // scope are held by it, so it is the context of the scope
        java.util.Optional<io.micronaut.cdi.internal.extension.ExtensionContexts> extensions = extensionContexts;
        if (extensions == null) {
            extensions = beanContext.findBean(io.micronaut.cdi.internal.extension.ExtensionContexts.class);
            extensionContexts = extensions;
        }
        if (extensions.isPresent()) {
            List<jakarta.enterprise.context.spi.AlterableContext> registered = extensions.get().contextsFor(scopeType);
            if (!registered.isEmpty()) {
                return new java.util.ArrayList<>(registered);
            }
        }
        if (scopeType == jakarta.enterprise.context.RequestScoped.class) {
            return requestContexts;
        }
        if (scopeType == jakarta.enterprise.context.ApplicationScoped.class) {
            return applicationContexts;
        }
        return List.of();
    }

    @Override
    public boolean isMatchingBean(Set<Type> beanTypes,
                                  Set<Annotation> beanQualifiers,
                                  Type requiredType,
                                  Set<Annotation> requiredQualifiers) {
        return CdiAssignability.isMatchingBean(types(), beanTypes, beanQualifiers, requiredType, requiredQualifiers);
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType,
                                   Set<Annotation> specifiedQualifiers,
                                   Type observedEventType,
                                   Set<Annotation> observedEventQualifiers) {
        return CdiAssignability.isMatchingEvent(types(), specifiedType, specifiedQualifiers, observedEventType,
            observedEventQualifiers);
    }

    @Override
    public io.micronaut.cdi.MicronautEvent<Object> getEvent() {
        return new CdiEvent<>(observers, Argument.OBJECT_ARGUMENT, Set.of());
    }

    @Override
    public io.micronaut.cdi.MicronautInstance<Object> createInstance() {
        return new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
    }

    /**
     * The bean context this reads.
     *
     * @return The bean context
     */
    public BeanContext beanContext() {
        return beanContext;
    }

    @Override
    public Object getInjectableReference(InjectionPoint injectionPoint, CreationalContext<?> ctx) {
        Set<Bean<?>> beans = getBeans(injectionPoint.getType(),
            injectionPoint.getQualifiers().toArray(new Annotation[0]));
        Bean<?> bean = resolve(beans);
        if (bean == null) {
            throw new UnsatisfiedResolutionException("No bean qualifies for " + injectionPoint);
        }
        return getReference(bean, injectionPoint.getType(), ctx);
    }

    @Override
    public boolean isPassivatingScope(Class<? extends Annotation> annotationType) {
        // a passivating scope belongs to CDI Full, and none of the scopes of CDI Lite is one
        return false;
    }

    @Override
    public Set<Annotation> getInterceptorBindingDefinition(Class<? extends Annotation> bindingType) {
        return definitionOf(bindingType, jakarta.interceptor.InterceptorBinding.class,
            "BeanManager.getInterceptorBindingDefinition");
    }

    @Override
    public Set<Annotation> getStereotypeDefinition(Class<? extends Annotation> stereotype) {
        return definitionOf(stereotype, jakarta.enterprise.inject.Stereotype.class,
            "BeanManager.getStereotypeDefinition");
    }

    /**
     * What an annotation is itself annotated with, which is what the specification calls the definition of an
     * interceptor binding or of a stereotype.
     *
     * <p>It is read off the annotation the author wrote rather than worked out again: a stereotype is a set of
     * annotations, and the set is the one written on it. The annotations are instances, which reading the
     * annotation class is what hands out.</p>
     */
    private Set<Annotation> definitionOf(Class<? extends Annotation> type, Class<? extends Annotation> required,
                                         String what) {
        CdiReflection reflection = CdiReflection.require(beanContext, what);
        if (!reflection.isAnnotated(type, required)) {
            throw new IllegalArgumentException(type.getName() + " is not annotated " + required.getName());
        }
        return reflection.metaAnnotationsOf(type);
    }

    @Override
    public boolean areQualifiersEquivalent(Annotation one, Annotation other) {
        return CdiAnnotations.areEquivalent(types(), one, other);
    }

    @Override
    public boolean areInterceptorBindingsEquivalent(Annotation one, Annotation other) {
        return CdiAnnotations.areEquivalent(types(), one, other);
    }

    @Override
    public int getQualifierHashCode(Annotation qualifier) {
        return CdiAnnotations.bindingHashCode(types(), qualifier);
    }

    @Override
    public int getInterceptorBindingHashCode(Annotation interceptorBinding) {
        return CdiAnnotations.bindingHashCode(types(), interceptorBinding);
    }

    @Override
    public void validate(InjectionPoint injectionPoint) {
        // an injection point is resolved as the bean that declares it is compiled, and one that could not be
        // resolved has already been reported by then
    }

    @Override
    public @Nullable Bean<?> getPassivationCapableBean(String id) {
        throw belongsToFull("A passivation capable bean");
    }

    @Override
    public List<jakarta.enterprise.inject.spi.Decorator<?>> resolveDecorators(Set<Type> types,
                                                                              Annotation... qualifiers) {
        throw belongsToFull("Decorators");
    }

    @Override
    public jakarta.el.ELResolver getELResolver() {
        return expressionLanguage().resolver();
    }

    @Override
    public jakarta.el.ExpressionFactory wrapExpressionFactory(jakarta.el.ExpressionFactory expressionFactory) {
        return expressionLanguage().wrap(expressionFactory);
    }

    private ExpressionLanguageBridge expressionLanguage() {
        // the expression language is a specification of its own, implemented by the optional module that
        // contributes this bridge; without that module the questions have no answer here
        return beanContext.findBean(ExpressionLanguageBridge.class)
            .orElseThrow(() -> new UnsupportedOperationException("The expression language is implemented by the "
                + "optional micronaut-cdi-el module, which is not on the classpath. See Conformance"));
    }

    @Override
    public <T> jakarta.enterprise.inject.spi.AnnotatedType<T> createAnnotatedType(Class<T> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> jakarta.enterprise.inject.spi.InjectionTargetFactory<T> getInjectionTargetFactory(
        jakarta.enterprise.inject.spi.AnnotatedType<T> annotatedType) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <X> jakarta.enterprise.inject.spi.ProducerFactory<X> getProducerFactory(
        jakarta.enterprise.inject.spi.AnnotatedField<? super X> field, Bean<X> declaringBean) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <X> jakarta.enterprise.inject.spi.ProducerFactory<X> getProducerFactory(
        jakarta.enterprise.inject.spi.AnnotatedMethod<? super X> method, Bean<X> declaringBean) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> jakarta.enterprise.inject.spi.BeanAttributes<T> createBeanAttributes(
        jakarta.enterprise.inject.spi.AnnotatedType<T> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public jakarta.enterprise.inject.spi.BeanAttributes<?> createBeanAttributes(
        jakarta.enterprise.inject.spi.AnnotatedMember<?> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> Bean<T> createBean(jakarta.enterprise.inject.spi.BeanAttributes<T> attributes, Class<T> beanClass,
                                  jakarta.enterprise.inject.spi.InjectionTargetFactory<T> factory) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T, X> Bean<T> createBean(jakarta.enterprise.inject.spi.BeanAttributes<T> attributes,
                                     Class<X> beanClass,
                                     jakarta.enterprise.inject.spi.ProducerFactory<X> producerFactory) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public InjectionPoint createInjectionPoint(jakarta.enterprise.inject.spi.AnnotatedField<?> field) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public InjectionPoint createInjectionPoint(jakarta.enterprise.inject.spi.AnnotatedParameter<?> parameter) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T extends jakarta.enterprise.inject.spi.Extension> T getExtension(Class<T> extensionClass) {
        throw belongsToFull("A portable extension");
    }

    @Override
    public <T> jakarta.enterprise.inject.spi.InterceptionFactory<T> createInterceptionFactory(
        CreationalContext<T> ctx, Class<T> clazz) {
        throw belongsToFull("An interception factory");
    }

    private static UnsupportedOperationException belongsToFull(String what) {
        return new UnsupportedOperationException(what + " belongs to CDI Full, which this module does not "
            + "implement. See Conformance");
    }

    /**
     * An injection point as the key of what it resolves to: the point itself, compared by identity.
     *
     * @param point The injection point
     */
    private record PointKey(Argument<?> point) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PointKey key && key.point == point;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(point);
        }
    }

    /**
     * What an injection point resolves to: the definition of the one bean the container selected, no bean at all,
     * or whatever Micronaut's rules resolve among the beans of Micronaut's own.
     */
    static final class Selection {
        /**
         * No bean the container selects satisfies the point.
         */
        static final Selection NONE = new Selection(null);
        /**
         * The point is resolved by Micronaut's rules among the beans of Micronaut's own.
         */
        static final Selection MICRONAUT = new Selection(null);

        private final @Nullable BeanDefinition<?> definition;

        private Selection(@Nullable BeanDefinition<?> definition) {
            this.definition = definition;
        }

        /**
         * The definition of the bean selected.
         *
         * @return The definition, or {@code null} where there is none, or Micronaut's rules resolve the point
         */
        @Nullable BeanDefinition<?> definition() {
            return definition;
        }

        /**
         * The qualifier Micronaut's rules resolve a point {@link #MICRONAUT} leaves to them by: the one Micronaut
         * reads off the point, among the beans of Micronaut's own alone.
         *
         * @param point     The injection point
         * @param qualifier The qualifier Micronaut handed over for the point, or {@code null} to read it off it
         * @param <T>       The bean type
         * @return The qualifier
         */
        static <T> io.micronaut.context.Qualifier<T> micronautQualifier(
            Argument<T> point, io.micronaut.context.@Nullable Qualifier<T> qualifier) {
            return NotOfTheSpecification.forPoint(point, qualifier);
        }
    }

    /**
     * The beans of the deployment, and the beans the container knows they were narrowed from.
     *
     * @param from  The beans the container knows
     * @param beans The beans of the deployment
     */
    private record Deployed(List<CdiBean<?>> from, List<CdiBean<?>> beans) {
    }
}
