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

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.cdi.internal.context.ApplicationScope;
import io.micronaut.cdi.internal.metadata.CdiBuiltIn;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.internal.metadata.CdiUnproxyable;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.cdi.spi.ExpressionLanguageBridge;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanDependencyGroup;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.interceptor.spi.InterceptorBindings;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.UnproxyableResolutionException;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMember;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanAttributes;
import jakarta.enterprise.inject.spi.Decorator;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionTargetFactory;
import jakarta.enterprise.inject.spi.InterceptionFactory;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.spi.ProducerFactory;
import jakarta.inject.Inject;
import jakarta.interceptor.InterceptorBinding;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
 * Lite are ones this container knows. The expression language operations are delegated to the optional
 * micronaut-cdi-el module, and fail with the name of that module where it is absent. Everything else on it that
 * belongs to CDI Full — decorators, passivation, portable extensions and building a bean out of an annotated
 * type — says so rather than answering with an empty result that would read as there being none.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@io.micronaut.context.annotation.Context
@CdiBuiltIn
@CdiScope("jakarta.enterprise.context.Dependent")
@Default
@Internal
public final class CdiBeanContainer implements BeanManager, MicronautBeanContainer {

    /**
     * The number of injection points what they resolve to is kept for.
     */
    private static final int MAX_SELECTIONS = 16_384;

    private final BeanContext beanContext;
    /**
     * The container's own bean of each definition the bean context handed it, for everyone who describes the
     * same bean to hold the same object, which reads the bean types of its definition once.
     */
    private final Map<BeanDefinition<?>, CdiBean<?>> canonical = new ConcurrentHashMap<>();
    /**
     * What each injection point resolves to ({@link #selectionFor}), by the identity of the point: the arguments of a
     * compiled definition are constants, and two points of one type and name may declare different qualifiers.
     * Emptied as synthetic beans are registered and portable extensions add qualifiers ({@link #refreshCandidates}).
     * Micronaut keeps the candidates of a type, a destroyed dependent's included, but matching them by the rules of
     * the specification and narrowing them is this container's work, which a dependent bean created over and over
     * would do again for each of its points (see Conformance).
     */
    private volatile Map<PointKey, Selection> selections = new ConcurrentHashMap<>();
    private volatile @Nullable RecordedTypeIndex typeIndex;
    private volatile @Nullable CompiledAnnotationTypes types;
    private boolean validated;
    private final ObserverRegistry observers;
    /**
     * The contexts of the scopes: the views of the built-in ones and the ones extensions registered.
     */
    private final CdiContexts contexts;
    /**
     * The dependency groups lookups opened after their owner had already destroyed what it held - a lookup that
     * escaped the bean it was injected into, used after that bean was destroyed - which no owner closes: closed as
     * the container stops, the latest first, so that what they created is destroyed then (see Conformance).
     */
    private final Deque<BeanDependencyGroup> lateGroups = new ConcurrentLinkedDeque<>();

    @Inject
    public CdiBeanContainer(BeanContext beanContext, RequestScope requestScope,
                            ApplicationScope applicationScope, ObserverRegistry observers) {
        this.beanContext = beanContext;
        this.observers = observers;
        this.contexts = new CdiContexts(beanContext, requestScope, applicationScope);
        // the container of a running application is what CDI.current() resolves to, and there is nothing else
        // for it to resolve through: the specification's own entry point is static. It is registered as the
        // container starts, which is what makes this bean one Micronaut creates eagerly rather than on demand
        CdiRunning.started(this);
    }

    /**
     * Takes the container of a context that failed to start off what {@code CDI.current()} resolves to: Micronaut
     * destroys the beans of a context that is running alone, so the container of one whose start failed is not
     * destroyed, and would otherwise stay the current one.
     *
     * @param beanContext The context that failed to start
     */
    public static void failedToStart(BeanContext beanContext) {
        CdiRunning.stoppedFor(beanContext);
    }

    @PreDestroy
    void stopped() {
        Throwable failure = null;
        for (BeanDependencyGroup group = lateGroups.pollLast(); group != null; group = lateGroups.pollLast()) {
            try {
                group.close();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        // no longer what CDI.current() resolves to, whatever a group threw as it closed
        CdiRunning.stopped(this);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    /**
     * Closes, as the container stops, a group a lookup opened after its owner had destroyed what it held, which no
     * owner closes.
     *
     * @param group The group
     */
    void closeAtShutdown(BeanDependencyGroup group) {
        lateGroups.add(group);
    }

    /**
     * Forgets a group {@link #closeAtShutdown} was handed that its owner closed after all.
     *
     * @param group The group
     */
    void forgetAtShutdown(BeanDependencyGroup group) {
        lateGroups.remove(group);
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
                throw new UnproxyableResolutionException(unproxyable);
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
    public Set<Bean<?>> getBeans(Argument<?> beanType, AnnotationValue<?> qualifier,
                                 AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length + 1);
        all.add(CdiQualifier.ofValue(types(), qualifier));
        all.addAll(qualifiersOf(qualifiers));
        return beansOf((Type) beanType, wellFormed(all));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Set<Bean<?>> getBeans(Argument<?> beanType, io.micronaut.context.Qualifier<?> qualifier) {
        // composed with the program's qualifier, which the bean context hands every eligible definition at once and
        // the type that was asked for, as Micronaut's own lookups hand it: a qualifier may choose among the
        // candidates - the closest type arguments - rather than test each on its own
        CdiBeanQualifier<Object> matching = matching(beanType, List.of(CdiQualifier.ANY));
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : beanContext.getBeanDefinitions(matching.lookupType(),
            Qualifiers.byQualifiers(matching,
                (io.micronaut.context.Qualifier<Object>) qualifier))) {
            beans.add(canonicalBean(definition));
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
     * ({@link Selection.Unselected#MICRONAUT}, resolved by {@link NonCdiBeanQualifier#forPoint}): a bean of the
     * specification the qualifiers of the point rejected is not admitted again by Micronaut's, by the prefix of its
     * name or by being primary. So is a type with a type variable among its arguments, which no bean type is.</p>
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Selection select(Argument<?> point) {
        CdiBeanQualifier<Object> matching;
        try {
            matching = matching(point, CdiQualifier.declared(point.getAnnotationMetadata()));
        } catch (IllegalArgumentException e) {
            // a type with a variable the declaring class gives a value the definition does not carry
            return Selection.Unselected.MICRONAUT;
        }
        // the candidates of the class of the point as the bean context enumerates them, which the qualifier of
        // the specification is then applied to: the same as asking for the definitions with the qualifier
        Collection<BeanDefinition<Object>> ofClass = beanContext.getBeanDefinitions(matching.lookupType());
        Class<?> raw = CdiTypes.classOf(point);
        boolean specification = false;
        boolean micronaut = false;
        for (BeanDefinition<?> definition : ofClass) {
            if (!CdiBeanQualifier.isInjectable(definition) || !hasTypeOfClass(definition, raw)) {
                // an interceptor is a bean of the specification of its class, though never one a point is
                // injected with, and a definition the context only indexes under the class is no bean of it:
                // neither says anything of which rules the point is resolved by
                continue;
            }
            if (CdiResolution.isBeanOfTheSpecification(definition)) {
                specification = true;
            } else {
                micronaut = true;
            }
            if (specification && micronaut) {
                break;
            }
        }
        if (!specification) {
            return Selection.Unselected.MICRONAUT;
        }
        Set<Bean<?>> eligible = new LinkedHashSet<>();
        for (BeanDefinition<Object> definition : matching.filterQualified(Object.class, ofClass)) {
            eligible.add(canonicalBean(definition));
        }
        if (micronaut && ofTheSpecification(eligible).isEmpty()) {
            // the beans of the specification of the class are not among the ones the qualifiers admit, and the
            // beans of Micronaut's own of it are left to Micronaut's rules
            return Selection.Unselected.MICRONAUT;
        }
        Bean<?> selected = resolve(eligible);
        return selected == null ? Selection.Unselected.UNSATISFIED
            : new Selection.Selected(((CdiBean<?>) selected).definition());
    }

    /**
     * Whether the definition is a bean with a bean type of the class, a primitive and its wrapper taken as one: what
     * a bean type matching a class or a parameterized type of the class requires. The built-in event and lookup
     * answer the classes they are built for, and a type that names no class - an array of a variable - and
     * {@code Object}, which every bean has, are answered by every bean.
     */
    private boolean hasTypeOfClass(BeanDefinition<?> definition, @Nullable Class<?> raw) {
        if (raw == null || raw == Object.class) {
            return true;
        }
        if (definition instanceof AbstractCdiInjectionPointBeanDefinition<?> builtIn) {
            return builtIn.isBeanType(raw);
        }
        Class<?> boxed = CdiTypes.boxedOf(raw);
        for (Argument<?> type : canonicalBean(definition).types()) {
            Class<?> own = CdiTypes.classOf(type);
            if (own != null && CdiTypes.boxedOf(own) == boxed) {
                return true;
            }
        }
        return false;
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
    <T> Collection<BeanDefinition<T>> definitionsOf(Argument<T> type, List<CdiQualifier> required,
                                                    io.micronaut.context.@Nullable Qualifier<?> narrowedBy) {
        CdiBeanQualifier<T> matching = matching(type, required);
        // the program's qualifier is handed every eligible definition at once, as Micronaut's own lookups hand it
        io.micronaut.context.Qualifier<T> qualifier = narrowedBy == null ? matching
            : Qualifiers.byQualifiers(matching,
                (io.micronaut.context.Qualifier<T>) narrowedBy);
        return beanContext.getBeanDefinitions(matching.lookupType(), qualifier);
    }

    /**
     * The beans of a type with the qualifiers, by the rules of section 2.4.2, before the alternatives have their
     * say: the one candidate selection of the container, which an injection point, an {@code Instance}, the bean
     * manager and the validation of the deployment all make. The bean context enumerates the candidates of the
     * class of the type, as it does for every lookup - less what is disabled, the targets of proxies, and a bean
     * another one replaces with Micronaut's {@code Replaces} - and the {@link CdiBeanQualifier qualifier of the
     * specification} keeps the ones it admits: one of whose recorded bean types matches the type - a type
     * Micronaut only indexes the definition under is none of them - and that has the qualifiers, with what a
     * portable extension added. An interceptor, which is never injected, is left out.
     *
     * @param beanType The required type
     * @param required The required qualifiers, none at all requiring the default qualifier
     * @return The beans
     * @throws IllegalArgumentException For a type with a type variable among its arguments
     */
    Set<Bean<?>> beansOf(Argument<?> beanType, List<CdiQualifier> required) {
        CdiBeanQualifier<Object> matching = matching(beanType, required);
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : beanContext.getBeanDefinitions(matching.lookupType(), matching)) {
            beans.add(canonicalBean(definition));
        }
        return beans;
    }

    private <T> CdiBeanQualifier<T> matching(Argument<?> beanType, List<CdiQualifier> required) {
        CdiAssignability.requireNoTypeVariable(beanType);
        return CdiBeanQualifier.of(this, beanType, required);
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

    private List<CdiQualifier> qualifiersOf(AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length);
        for (AnnotationValue<?> qualifier : qualifiers) {
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
     *
     * @return The index of this container
     */
    RecordedTypeIndex typeIndex() {
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
     * the same object: the one {@code getBeans} answers with. The definition of the class of a bean in a normal
     * scope - what its client proxy stands in front of, which a resolution creating the instance names - is the
     * bean of the proxy.
     *
     * @param definition The definition
     * @return The bean
     */
    public CdiBean<?> canonicalBean(BeanDefinition<?> definition) {
        CdiBean<?> known = canonical.get(definition);
        if (known != null) {
            return known;
        }
        BeanDefinition<?> described = proxyOf(definition);
        CdiBean<?> bean = canonical.computeIfAbsent(described, key -> new CdiBean<>(beanContext, key));
        if (described != definition) {
            canonical.putIfAbsent(definition, bean);
        }
        return bean;
    }

    /**
     * The definition of the client proxy that stands in front of the given definition, where it is the target of
     * one, which the bean context does not enumerate: the bean is the proxy.
     */
    private BeanDefinition<?> proxyOf(BeanDefinition<?> definition) {
        if (definition instanceof ProxyBeanDefinition<?>
            || !CdiResolution.isNormalScoped(definition.getAnnotationMetadata())) {
            return definition;
        }
        for (BeanDefinition<?> candidate : beanContext.getBeanDefinitions(Argument.of(definition.getBeanType()))) {
            if (candidate instanceof ProxyBeanDefinition<?> proxy
                && proxy.getTargetDefinitionType() == definition.getClass()) {
                return candidate;
            }
        }
        return definition;
    }

    /**
     * Forgets what the injection points were resolved to, so that beans registered while the container starts —
     * the synthetic ones of section 2.10.5 — and qualifiers the portable extensions added are seen by the next
     * resolution.
     */
    public void refreshCandidates() {
        selections = new ConcurrentHashMap<>();
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
     * The beans of the deployment: every bean the bean context enumerates - everything that was compiled, minus
     * what is disabled, minus what an environment narrowed away, and minus the ones another one replaces with
     * Micronaut's {@code Replaces}, which are no beans of it, not of their name and not with dependencies to
     * validate - that is a {@linkplain CdiBeanQualifier#isBean bean of the container}.
     *
     * @return The beans
     */
    List<CdiBean<?>> beans() {
        List<CdiBean<?>> beans = new ArrayList<>();
        for (BeanDefinition<Object> definition : beanContext.getBeanDefinitions(Argument.OBJECT_ARGUMENT)) {
            if (CdiBeanQualifier.isBean(definition)) {
                beans.add(canonicalBean(definition));
            }
        }
        return beans;
    }

    @Override
    public Set<Bean<?>> getBeans(String name) {
        // the definitions are read for the exact name the bean was recorded with, and only a bean of the name is
        // described. Micronaut's lookup by name is no narrower: it falls back to the simple name of the class of an
        // unnamed bean, and reads the name Micronaut materialized rather than one a stereotype gave (CdiName)
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (BeanDefinition<Object> definition : beanContext.getBeanDefinitions(Argument.OBJECT_ARGUMENT)) {
            if (name.equals(CdiBean.nameOf(definition)) && CdiBeanQualifier.isBean(definition)) {
                beans.add(canonicalBean(definition));
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
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, AnnotationValue<?> qualifier,
                                                                     AnnotationValue<?>... qualifiers) {
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
        Set<Class<? extends Annotation>> distinct = new HashSet<>();
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
        InterceptorBindings given = InterceptorBindings
            .ofValues(bindingValuesOf(CdiQualifier.ofInstances(types(), interceptorBindings)));
        // selected as the interceptors implementation selects the chain of an element bound by these bindings: the
        // enabled interceptor classes bound to them, by priority, then by class name, with the ones the SE bootstrap
        // listed after them in the order of the list (CdiInterceptorEnablement), so that what is reported here is
        // what is invoked
        List<Interceptor<?>> resolved = new ArrayList<>();
        for (BeanDefinition<?> definition : InterceptorBindings
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
        List<AnnotationValue<?>> values = new ArrayList<>(bindings.size());
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
        return contexts.isScope(annotationType);
    }

    @Override
    public boolean isNormalScope(Class<? extends Annotation> annotationType) {
        return contexts.isNormalScope(annotationType);
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
            .isAnnotated(annotationType, Stereotype.class);
    }

    @Override
    public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
        Boolean recorded = types().isInterceptorBinding(annotationType.getName());
        if (recorded != null) {
            return recorded;
        }
        return reflection("BeanContainer.isInterceptorBinding", annotationType)
            .isAnnotated(annotationType, InterceptorBinding.class);
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        Context active = activeContext(scopeType);
        if (active != null) {
            return active;
        }
        throw new ContextNotActiveException("No context of "
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
        return contexts.active(scopeType);
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        return contexts.of(scopeType);
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
    public MicronautEvent<Object> getEvent() {
        return new CdiEvent<>(observers, Argument.OBJECT_ARGUMENT, Set.of());
    }

    @Override
    public MicronautInstance<Object> createInstance() {
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
        return definitionOf(bindingType, InterceptorBinding.class,
            "BeanManager.getInterceptorBindingDefinition");
    }

    @Override
    public Set<Annotation> getStereotypeDefinition(Class<? extends Annotation> stereotype) {
        return definitionOf(stereotype, Stereotype.class,
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
    public List<Decorator<?>> resolveDecorators(Set<Type> types,
                                                Annotation... qualifiers) {
        throw belongsToFull("Decorators");
    }

    @Override
    public ELResolver getELResolver() {
        return expressionLanguage().resolver();
    }

    @Override
    public ExpressionFactory wrapExpressionFactory(ExpressionFactory expressionFactory) {
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
    public <T> AnnotatedType<T> createAnnotatedType(Class<T> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> InjectionTargetFactory<T> getInjectionTargetFactory(
        AnnotatedType<T> annotatedType) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(
        AnnotatedField<? super X> field, Bean<X> declaringBean) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(
        AnnotatedMethod<? super X> method, Bean<X> declaringBean) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> BeanAttributes<T> createBeanAttributes(
        AnnotatedType<T> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public BeanAttributes<?> createBeanAttributes(
        AnnotatedMember<?> type) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T> Bean<T> createBean(BeanAttributes<T> attributes, Class<T> beanClass,
                                  InjectionTargetFactory<T> factory) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T, X> Bean<T> createBean(BeanAttributes<T> attributes,
                                     Class<X> beanClass,
                                     ProducerFactory<X> producerFactory) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedField<?> field) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedParameter<?> parameter) {
        throw belongsToFull("Building a bean out of an annotated type");
    }

    @Override
    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        throw belongsToFull("A portable extension");
    }

    @Override
    public <T> InterceptionFactory<T> createInterceptionFactory(
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
     * What an injection point the container resolves resolves to: the definition of the one bean the container
     * selected ({@link Selected}), no bean at all ({@link Unselected#UNSATISFIED}), or whatever Micronaut's rules
     * resolve among the beans of Micronaut's own ({@link Unselected#MICRONAUT}, by the qualifier
     * {@link NonCdiBeanQualifier#forPoint}). A point the processor left unmarked is not one the container selects
     * for at all, and has no selection.
     */
    sealed interface Selection permits Selection.Selected, Selection.Unselected {

        /**
         * The one bean the container selected.
         *
         * @param definition Its definition
         */
        record Selected(BeanDefinition<?> definition) implements Selection {
        }

        /**
         * No bean the container selected.
         */
        enum Unselected implements Selection {
            /**
             * No bean the container selects satisfies the point.
             */
            UNSATISFIED,
            /**
             * The point is resolved by Micronaut's rules among the beans of Micronaut's own.
             */
            MICRONAUT
        }
    }
}
