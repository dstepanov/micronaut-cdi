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
import io.micronaut.cdi.spi.ExpressionLanguageBridge;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.BeanContext;
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

    private final BeanContext beanContext;
    private final RequestScope requestScope;
    private final io.micronaut.cdi.internal.context.ApplicationScope applicationScope;
    private volatile @Nullable List<CdiBean<?>> beans;
    private boolean validated;
    private final ObserverRegistry observers;

    @jakarta.inject.Inject
    public CdiBeanContainer(BeanContext beanContext, RequestScope requestScope,
                            io.micronaut.cdi.internal.context.ApplicationScope applicationScope, ObserverRegistry observers) {
        this.beanContext = beanContext;
        this.requestScope = requestScope;
        this.applicationScope = applicationScope;
        this.observers = observers;
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
            amongTheTypes = CdiAssignability.isTypeMatching(types, required);
        }
        if (!amongTheTypes) {
            throw new IllegalArgumentException("The type " + beanType.getTypeName()
                + " is not among the bean types of " + bean);
        }
        if (bean instanceof CdiBean<?> cdiBean) {
            String unproxyable = cdiBean.definition().getAnnotationMetadata()
                .stringValue("io.micronaut.cdi.internal.metadata.CdiUnproxyable").orElse(null);
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
        return new CdiCreationalContext<>();
    }

    @Override
    public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
        return getBeans(beanType, wellFormed(CdiQualifier.ofInstances(qualifiers)));
    }

    @Override
    public Set<Bean<?>> getBeans(Argument<?> beanType, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                 io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length + 1);
        all.add(CdiQualifier.ofValue(qualifier));
        all.addAll(qualifiersOf(qualifiers));
        return getBeans((Type) beanType, wellFormed(all));
    }

    private Set<Bean<?>> getBeans(Type required, List<CdiQualifier> requiredQualifiers) {
        // Micronaut's own form of a type is one of the types a lookup may be made by
        return beansOf(SpecificationTypes.argumentOf(required), requiredQualifiers);
    }

    Set<Bean<?>> beansOf(Argument<?> beanType, List<CdiQualifier> required) {
        Set<Bean<?>> beans = new LinkedHashSet<>();
        for (CdiBean<?> bean : candidates()) {
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
            if (CdiAssignability.isTypeMatching(bean.types(), requireNoTypeVariable(beanType))
                && CdiAssignability.areQualifiersMatching(bean.qualifiers(), required)) {
                beans.add(bean);
            }
        }
        return beans;
    }

    private static Argument<?> requireNoTypeVariable(Argument<?> required) {
        CdiAssignability.requireNoTypeVariable(required);
        return required;
    }

    private static List<CdiQualifier> qualifiersOf(io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length);
        for (io.micronaut.core.annotation.AnnotationValue<?> qualifier : qualifiers) {
            all.add(CdiQualifier.ofValue(qualifier));
        }
        return all;
    }

    /**
     * What the specification requires of the qualifiers a lookup names: each is a qualifier, and no qualifier
     * type is named twice unless it is repeatable.
     */
    private static List<CdiQualifier> wellFormed(List<CdiQualifier> qualifiers) {
        CdiQualifier.requireWellFormed(List.of(), qualifiers);
        return qualifiers;
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
    /**
     * The container's own bean for the given definition, so that everyone who describes the same bean holds
     * the same object: the one {@code getBeans} answers with.
     *
     * @param definition The definition
     * @return The bean
     */
    public CdiBean<?> canonicalBean(io.micronaut.inject.BeanDefinition<?> definition) {
        CdiBean<?> described = new CdiBean<>(beanContext, definition);
        for (CdiBean<?> candidate : candidates()) {
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
     * The beans the container knows.
     *
     * @return The beans
     */
    List<CdiBean<?>> beans() {
        return candidates();
    }

    private List<CdiBean<?>> candidates() {
        List<CdiBean<?>> resolved = beans;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (beans == null) {
                beans = loadCandidates();
            }
            return beans;
        }
    }

    private List<CdiBean<?>> loadCandidates() {
        // the references are the ones the beans predicate of the context admits, so an environment that
        // narrowed the context reports exactly the beans it narrowed it to
        Map<String, BeanDefinition<?>> byName = new java.util.LinkedHashMap<>();
        for (io.micronaut.inject.BeanDefinitionReference<Object> reference
            : beanContext.getBeanDefinitionReferences()) {
            try {
                BeanDefinition<?> definition = reference.load(beanContext);
                if (definition.isEnabled(beanContext) && !definition.isAbstract()) {
                    // an abstract class is not a bean of the specification, however it is annotated
                    byName.put(definition.getClass().getName(), definition);
                }
            } catch (RuntimeException | LinkageError e) {
                // a definition that cannot be loaded here is not a bean of this container
            }
        }
        // a bean the container was handed at runtime — a synthetic bean an extension described — has a
        // definition without a compiled reference. Such definitions share a class, so the instance tells
        // them apart
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            if (definition.isAbstract()) {
                continue;
            }
            String key = definition.getClass().getName();
            BeanDefinition<?> existing = byName.get(key);
            if (existing != null && existing != definition && !existing.equals(definition)) {
                key = key + "@" + System.identityHashCode(definition);
            }
            byName.putIfAbsent(key, definition);
        }
        // a bean in a normal scope is two definitions, the proxy and its target; the proxy is the bean
        Set<String> proxied = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : byName.values()) {
            if (definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy) {
                proxied.add(proxy.getTargetDefinitionType().getName());
            }
        }
        List<CdiBean<?>> loaded = new java.util.ArrayList<>();
        for (BeanDefinition<?> definition : byName.values()) {
            if (proxied.contains(definition.getClass().getName())) {
                continue;
            }
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
        for (CdiBean<?> bean : candidates()) {
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
        // more than one bean qualifies: the alternatives outrank everything else, and among them the one of
        // the highest priority wins. Anything short of a single winner is the ambiguity of section 2.4.2
        List<Bean<? extends X>> alternatives = new ArrayList<>();
        for (Bean<? extends X> bean : beans) {
            if (bean.isAlternative()) {
                alternatives.add(bean);
            }
        }
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }
        if (alternatives.size() > 1) {
            List<Bean<? extends X>> best = new ArrayList<>();
            int bestPriority = Integer.MIN_VALUE;
            for (Bean<? extends X> alternative : alternatives) {
                int priority = priorityOf(alternative);
                if (priority > bestPriority) {
                    bestPriority = priority;
                    best.clear();
                }
                if (priority == bestPriority) {
                    best.add(alternative);
                }
            }
            if (best.size() == 1) {
                return best.get(0);
            }
        }
        throw new AmbiguousResolutionException("More than one bean qualifies: " + beans);
    }

    private static int priorityOf(Bean<?> bean) {
        if (bean instanceof CdiBean<?> cdiBean) {
            return CdiResolution.priorityOf(cdiBean.definition());
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, Annotation... qualifiers) {
        return resolveObserverMethods(event, wellFormed(CdiQualifier.ofInstances(qualifiers)));
    }

    @Override
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                                                     io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        List<CdiQualifier> all = new ArrayList<>(qualifiers.length + 1);
        all.add(CdiQualifier.ofValue(qualifier));
        all.addAll(qualifiersOf(qualifiers));
        return resolveObserverMethods(event, wellFormed(all));
    }

    @SuppressWarnings("unchecked")
    private <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, List<CdiQualifier> qualifiers) {
        if (CdiTypes.declaresTypeVariables(event.getClass())) {
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
            if (!distinct.add(binding.annotationType())) {
                throw new IllegalArgumentException("The interceptor binding " + binding.annotationType().getName()
                    + " was given twice");
            }
        }
        List<CdiInterceptor<?>> resolved = new java.util.ArrayList<>();
        for (CdiBean<?> candidate : candidates()) {
            BeanDefinition<?> definition = candidate.definition();
            if (!definition.getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor")) {
                continue;
            }
            CdiInterceptor<?> interceptor = new CdiInterceptor<>(beanContext, definition);
            if (!interceptor.isEnabled() || !interceptor.intercepts(type)) {
                continue;
            }
            if (isBoundBy(interceptor, interceptorBindings)) {
                resolved.add(interceptor);
            }
        }
        // ordered as the interceptors implementation orders the chain that runs, equal priorities by class name,
        // so that what is reported here is what is invoked
        resolved.sort(java.util.Comparator.<CdiInterceptor<?>>comparingInt(CdiInterceptor::priority)
            .thenComparing(interceptor -> interceptor.getBeanClass().getName()));
        // the ones the SE bootstrap listed come after the ones ordered by priority, in the order of the list
        List<CdiInterceptor<?>> listed = new java.util.ArrayList<>();
        resolved.removeIf(interceptor -> interceptor.position() >= 0 && listed.add(interceptor));
        listed.sort(java.util.Comparator.comparingInt(CdiInterceptor::position));
        resolved.addAll(listed);
        return List.copyOf(resolved);
    }

    /**
     * Whether an interceptor is bound by the given bindings: every binding the interceptor declares has an
     * equivalent among them.
     */
    private static boolean isBoundBy(CdiInterceptor<?> interceptor, Annotation[] interceptorBindings) {
        List<CdiQualifier> declared = interceptor.bindings();
        if (declared.isEmpty()) {
            return false;
        }
        List<CdiQualifier> given = CdiQualifier.ofInstances(interceptorBindings);
        for (CdiQualifier binding : declared) {
            if (!binding.isAmong(given)) {
                return false;
            }
        }
        return true;
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
        Boolean known = CdiQualifier.isKnownQualifier(annotationType.getName());
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
        BindingTypes.BindingType recorded = BindingTypes.of(annotationType.getName());
        if (recorded != null) {
            return recorded.binding();
        }
        return reflection("BeanContainer.isInterceptorBinding", annotationType)
            .isAnnotated(annotationType, jakarta.interceptor.InterceptorBinding.class);
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
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
        if (active != null) {
            return active;
        }
        throw new jakarta.enterprise.context.ContextNotActiveException("No context of "
            + scopeType.getName() + " is active on the current thread");
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        if (scopeType == Singleton.class) {
            return List.of(CdiContext.ofSingleton(scopeType, beanContext));
        }
        if (scopeType == Dependent.class) {
            return List.of(CdiContext.holdingNothing(scopeType));
        }
        // a scope an extension registered a context for (section 2.10.1). A context a portable extension added
        // for the request or the application scope takes the place of the container's own: the beans of the
        // scope are held by it, so it is the context of the scope
        java.util.Optional<io.micronaut.cdi.internal.extension.ExtensionContexts> extensionContexts =
            beanContext.findBean(io.micronaut.cdi.internal.extension.ExtensionContexts.class);
        if (extensionContexts.isPresent()) {
            List<Context> registered = new java.util.ArrayList<>(
                extensionContexts.get().contextsFor(scopeType));
            if (!registered.isEmpty()) {
                return registered;
            }
        }
        if (scopeType == jakarta.enterprise.context.RequestScoped.class) {
            return List.of(CdiContext.ofRequest(scopeType, requestScope));
        }
        if (scopeType == jakarta.enterprise.context.ApplicationScoped.class) {
            return List.of(CdiContext.ofApplication(scopeType, applicationScope));
        }
        return List.of();
    }

    @Override
    public boolean isMatchingBean(Set<Type> beanTypes,
                                  Set<Annotation> beanQualifiers,
                                  Type requiredType,
                                  Set<Annotation> requiredQualifiers) {
        return CdiAssignability.isMatchingBean(beanTypes, beanQualifiers, requiredType, requiredQualifiers);
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType,
                                   Set<Annotation> specifiedQualifiers,
                                   Type observedEventType,
                                   Set<Annotation> observedEventQualifiers) {
        return CdiAssignability.isMatchingEvent(specifiedType, specifiedQualifiers, observedEventType,
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
        return CdiAnnotations.areEquivalent(one, other);
    }

    @Override
    public boolean areInterceptorBindingsEquivalent(Annotation one, Annotation other) {
        return CdiAnnotations.areEquivalent(one, other);
    }

    @Override
    public int getQualifierHashCode(Annotation qualifier) {
        return CdiAnnotations.bindingHashCode(qualifier);
    }

    @Override
    public int getInterceptorBindingHashCode(Annotation interceptorBinding) {
        return CdiAnnotations.bindingHashCode(interceptorBinding);
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
}
