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
package io.micronaut.cdi.runtime;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * The programmatic lookup of section 2.4.6, which is a typesafe resolution a program performs itself rather than
 * one the container performs for an injection point.
 *
 * <p>It is the same resolution either way: a type and a set of qualifiers, resolved against the beans of the
 * container. What programmatic lookup adds is that the resolution can be narrowed a step at a time — a
 * {@code select} returns another lookup of the narrower type and the qualifiers of both — and that it can be
 * asked whether it resolves to nothing or to more than one bean rather than failing.</p>
 *
 * @param <T> The type being looked up
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInstance<T> implements io.micronaut.cdi.MicronautInstance<T>, AutoCloseable {

    private final BeanContext beanContext;
    private final Argument<T> type;
    private final List<CdiQualifier> qualifiers;
    private final jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt;
    private final java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated;

    public CdiInstance(BeanContext beanContext, Argument<T> type, Annotation... qualifiers) {
        this(beanContext, null, type, CdiQualifier.ofInstances(qualifiers));
    }

    CdiInstance(BeanContext beanContext,
                jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt,
                Argument<T> type, List<CdiQualifier> qualifiers) {
        this(beanContext, injectedAt,
            java.util.Collections.synchronizedList(new java.util.ArrayList<>(2)), type, qualifiers);
    }

    private CdiInstance(BeanContext beanContext,
                        jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt,
                        java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated,
                        Argument<T> type, List<CdiQualifier> qualifiers) {
        this.beanContext = beanContext;
        this.injectedAt = injectedAt;
        this.transientlyCreated = transientlyCreated;
        this.type = type;
        this.qualifiers = qualifiers;
    }

    @Override
    public io.micronaut.cdi.MicronautInstance<T> select(Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, type,
            and(CdiQualifier.ofInstances(qualifiers)));
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, Argument.of(subtype),
            and(CdiQualifier.ofInstances(qualifiers)));
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautInstance<U> select(TypeLiteral<U> subtype,
                                                                      Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated,
            CdiTypes.argumentOf(subtype.getType()), and(CdiQualifier.ofInstances(qualifiers)));
    }

    @Override
    public io.micronaut.cdi.MicronautInstance<T> select(io.micronaut.core.annotation.AnnotationValue<?> qualifier,
                                                        io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, type, and(valuesOf(qualifier, qualifiers)));
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautInstance<U> select(
        Class<U> subtype, io.micronaut.core.annotation.AnnotationValue<?> qualifier,
        io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, Argument.of(subtype),
            and(valuesOf(qualifier, qualifiers)));
    }

    @Override
    public <U extends T> io.micronaut.cdi.MicronautInstance<U> select(
        Argument<U> subtype, io.micronaut.core.annotation.AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, subtype,
            and(valuesOf(null, qualifiers)));
    }

    static List<CdiQualifier> valuesOf(io.micronaut.core.annotation.@Nullable AnnotationValue<?> first,
                                       io.micronaut.core.annotation.AnnotationValue<?>... more) {
        List<CdiQualifier> all = new ArrayList<>(more.length + 1);
        if (first != null) {
            all.add(CdiQualifier.ofValue(first));
        }
        for (io.micronaut.core.annotation.AnnotationValue<?> value : more) {
            all.add(CdiQualifier.ofValue(value));
        }
        return all;
    }

    /**
     * The lookup narrowed to the given argument, which carries the type arguments an injection point declared,
     * and to the given qualifiers.
     *
     * @param argument   The argument
     * @param qualifiers The qualifiers, which replace the ones of this lookup
     * @param <U>        The type
     * @return The lookup
     */
    <U> CdiInstance<U> selectArgument(Argument<U> argument, List<CdiQualifier> qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, argument, qualifiers);
    }

    private List<CdiQualifier> and(List<CdiQualifier> more) {
        CdiQualifier.requireWellFormed(qualifiers, more);
        List<CdiQualifier> all = new ArrayList<>(qualifiers.size() + more.size());
        all.addAll(qualifiers);
        all.addAll(more);
        return all;
    }

    @Override
    public T get() {
        BeanDefinition<T> definition = one();
        return CdiResolution.isDependent(definition) ? dependent(definition) : resolve(beanContext, definition);
    }

    /**
     * Creates a dependent instance of the bean for this lookup, whichever way it was asked for: by
     * {@code get()} or by iterating.
     */
    private T dependent(BeanDefinition<T> definition) {
        // a dependent instance obtained through this lookup belongs to the bean the lookup was injected
        // into, and is destroyed with it — which is when this lookup itself is closed. Section 2.5.2.5
        // gives it the lookup's own injection point as its metadata, which is left out for its creation
        io.micronaut.context.BeanRegistration<T> registration =
            createDependent(beanContext, type, definition, lookupPoint());
        transientlyCreated.add(registration);
        return registration.bean();
    }

    /**
     * Creates a dependent instance for a lookup, however the lookup hands it out - {@code get()}, iterating, or
     * a handle - so that what its creation throws comes out the same from each.
     *
     * @param beanContext The bean context
     * @param selected    The type the lookup selected
     * @param definition  The bean
     * @param lookedUpAt  The injection point of the lookup, which the instance is given as its own, or {@code null}
     * @param <U>         The bean type
     * @return The registration that created the instance, which knows the dependents to destroy with it
     */
    static <U> io.micronaut.context.BeanRegistration<U> createDependent(
        BeanContext beanContext, Argument<U> selected, BeanDefinition<U> definition,
        jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint lookedUpAt) {
        if (lookedUpAt != null) {
            CurrentInjectionPoint.enter(lookedUpAt);
        }
        try {
            return beanContext.getBeanRegistration(askedAs(selected, definition), only(definition));
        } catch (io.micronaut.context.exceptions.BeanCreationException e) {
            // what the bean's own code — or an interceptor around its construction — threw comes out as
            // it was thrown when it is unchecked, and wrapped when it is checked (section 6.1.1)
            throw CdiBean.translated(e);
        } finally {
            if (lookedUpAt != null) {
                CurrentInjectionPoint.leave();
            }
        }
    }

    /**
     * Resolves the instance of a bean that has a scope, which may be what creates it: what its creation throws
     * comes out as what a dependent creation throws does.
     */
    static <U> U resolve(BeanContext beanContext, BeanDefinition<U> definition) {
        try {
            return beanContext.getBean(definition);
        } catch (io.micronaut.context.exceptions.BeanCreationException e) {
            throw CdiBean.translated(e);
        }
    }

    /**
     * The argument a dependent creation is asked with: the type the lookup selected, generics included, so
     * that a parameterized built-in — an {@code Event<X>}, an {@code Instance<X>} — reads what it is for, and
     * a bean resolvable only by its declared types is asked by one of them. The definition's own type serves
     * only where the selected one does not name the bean — a primitive selected where the boxed type is the
     * bean's, say.
     */
    static <U> Argument<U> askedAs(Argument<U> selected, BeanDefinition<U> definition) {
        if (definition.isCandidateBean(selected)) {
            if (selected.getTypeParameters().length > 0) {
                // a parameterized selection carries what a parameterized built-in reads: what an Event<X> is of
                return selected;
            }
            java.util.Set<Class<?>> exposed = definition.getExposedTypes();
            if (!exposed.isEmpty() && !exposed.contains(definition.asArgument().getType())) {
                // the definition does not expose its own type — a synthetic bean resolvable only by what it
                // declared — so it can only be asked by the selected one
                return selected;
            }
        }
        return definition.asArgument();
    }

    private static <U> io.micronaut.context.Qualifier<U> only(BeanDefinition<U> definition) {
        return new io.micronaut.context.Qualifier<U>() {
            @Override
            public <BT extends io.micronaut.inject.BeanType<U>> java.util.stream.Stream<BT> reduce(
                Class<U> beanType, java.util.stream.Stream<BT> candidates) {
                return candidates.filter(candidate -> candidate.equals(definition));
            }
        };
    }

    /**
     * Lets go of every dependent instance this lookup created, which happens when the bean the lookup belongs
     * to is destroyed.
     */
    @Override
    public void close() {
        destroyTransients();
    }

    /**
     * Everything tracked so far, taken out atomically so that no registration is destroyed twice and none is
     * lost to a concurrent add.
     */
    private java.util.List<io.micronaut.context.BeanRegistration<?>> drainTransients() {
        synchronized (transientlyCreated) {
            java.util.List<io.micronaut.context.BeanRegistration<?>> drained =
                new java.util.ArrayList<>(transientlyCreated);
            transientlyCreated.clear();
            return drained;
        }
    }

    /**
     * Destroys every dependent instance this lookup created, through the context so that the whole destruction
     * lifecycle runs: what section 2.10.5 asks for the lookup handed to a synthetic bean's creation and
     * disposal functions, whose dependent instances are destroyed once the function's work is done with.
     */
    public void destroyTransients() {
        // one throwing @PreDestroy must not leave the rest undestroyed: every registration is attempted, and
        // the first failure is what comes out
        RuntimeException failure = null;
        for (io.micronaut.context.BeanRegistration<?> registration : drainTransients()) {
            try {
                registration.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * The one definition the lookup resolves to, narrowed the way section 2.4.2 narrows several candidates.
     */
    private BeanDefinition<T> one() {
        java.util.List<BeanDefinition<T>> candidates = CdiResolution.narrow(definitions());
        if (candidates.isEmpty()) {
            throw new UnsatisfiedResolutionException("No bean of type " + type.getTypeName() + " qualifies");
        }
        if (candidates.size() > 1) {
            throw new AmbiguousResolutionException("More than one bean of type " + type.getTypeName()
                + " qualifies: " + candidates);
        }
        return candidates.get(0);
    }

    @Override
    public Iterator<T> iterator() {
        List<T> beans = new ArrayList<>();
        for (BeanDefinition<T> definition : CdiResolution.narrow(definitions())) {
            // a dependent instance obtained through iteration belongs to the lookup the same way one obtained
            // through get() does: it has the lookup's injection point, and is destroyed with the lookup
            beans.add(CdiResolution.isDependent(definition) ? dependent(definition) : resolve(beanContext, definition));
        }
        return beans.iterator();
    }

    @Override
    public boolean isUnsatisfied() {
        return definitions().isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return CdiResolution.narrow(definitions()).size() > 1;
    }

    @Override
    public void destroy(T instance) {
        // the instance this lookup created is destroyed through the registration that created it, which is
        // what knows the dependents that were created along with it
        io.micronaut.context.BeanRegistration<?> tracked = null;
        synchronized (transientlyCreated) {
            for (java.util.Iterator<io.micronaut.context.BeanRegistration<?>> created =
                 transientlyCreated.iterator(); created.hasNext();) {
                io.micronaut.context.BeanRegistration<?> registration = created.next();
                if (registration.bean() == instance) {
                    created.remove();
                    tracked = registration;
                    break;
                }
            }
        }
        if (tracked != null) {
            tracked.close();
            return;
        }
        Collection<BeanDefinition<T>> definitions = definitions();
        // the bean the instance is an instance of is the one whose registration holds it. The class of the
        // instance does not say: two producers of one class are two beans, each with a disposer of its own
        BeanDefinition<T> holding = beanContext.findBeanRegistration(instance)
            .map(io.micronaut.context.BeanRegistration::getBeanDefinition)
            .filter(definitions::contains)
            .orElse(null);
        if (holding != null) {
            destroyResolved(beanContext, holding, instance);
            return;
        }
        for (BeanDefinition<T> definition : definitions) {
            if (definition.getBeanType().isInstance(instance)) {
                destroyResolved(beanContext, definition, instance);
                return;
            }
        }
        beanContext.destroyBean(instance);
    }

    /**
     * Destroys what a lookup resolved: a bean in a normal scope by destroying the instance its context holds —
     * what was handed out is a client proxy — and any other through its definition.
     */
    static <T> void destroyResolved(BeanContext beanContext, BeanDefinition<T> definition, T instance) {
        if (isNormalScoped(definition)) {
            CdiBean<T> bean = new CdiBean<>(beanContext, definition);
            jakarta.enterprise.context.spi.Context context = beanContext
                .getBean(CdiBeanContainer.class).getContext(bean.getScope());
            if (context instanceof jakarta.enterprise.context.spi.AlterableContext alterable) {
                alterable.destroy(bean);
                return;
            }
        }
        destroy(beanContext, definition, instance);
    }

    private static boolean isNormalScoped(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal")
            .orElse(false);
    }

    /**
     * Destroys an instance through the definition it was resolved from.
     *
     * <p>Destroying it by the instance alone is not enough: Micronaut resolves the definition from the class of
     * the bean, and where the same class is produced by more than one producer there is more than one definition
     * of it and no way to tell which. The definition is known here, since it is what the lookup resolved.</p>
     */
    private static <T> void destroy(BeanContext beanContext, BeanDefinition<T> definition, T instance) {
        beanContext.destroyBean(
            BeanRegistration.of(beanContext, BeanIdentifier.of(definition.getName()), definition, instance)
        );
    }

    @Override
    public Handle<T> getHandle() {
        return new CdiHandle<>(beanContext, type, one(), lookupPoint(), transientlyCreated);
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        // each iteration is a fresh set of handles, the specification says: a handle resolved or destroyed on
        // one pass must not be what a second pass hands out
        return () -> CdiResolution.narrow(definitions()).stream()
            .map(definition -> (Handle<T>) new CdiHandle<>(beanContext, type, definition, lookupPoint(),
                transientlyCreated))
            .iterator();
    }

    /**
     * The injection point a dependent instance obtained through this lookup gets as its metadata: the point
     * the lookup was injected into where there is one (section 2.5.2.5), and otherwise the lookup itself,
     * described with the type it was selected as.
     */
    private jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint lookupPoint() {
        if (injectedAt != null) {
            return injectedAt instanceof CdiInjectionPoint described
                ? described.viewedAs(type, qualifiers)
                : injectedAt;
        }
        if (jakarta.enterprise.inject.spi.InjectionPoint.class.isAssignableFrom(type.getType())) {
            // a programmatic lookup OF the injection point metadata asks about the point already current —
            // it is not itself a place a bean is being injected, and must not shadow the answer
            return null;
        }
        // a lookup that was injected nowhere - the bean container's, CDI.current(), the SE container - stands
        // for itself, and requires what it selected: the type, and every qualifier of every select, with the
        // members each was handed over with (section 2.4.5.7)
        return new CdiInjectionPoint(null, type, null, null, false).viewedAs(type, qualifiers);
    }

    private Collection<BeanDefinition<T>> definitions() {
        Collection<BeanDefinition<T>> resolved = definitionsOf(type);
        Argument<T> counterpart = CdiTypes.counterpartOf(type);
        if (counterpart == null) {
            return resolved;
        }
        // a primitive and the class that boxes it are one bean type, and Micronaut keeps them apart
        Collection<BeanDefinition<T>> boxed = definitionsOf(counterpart);
        if (boxed.isEmpty()) {
            return resolved;
        }
        List<BeanDefinition<T>> both = new ArrayList<>(resolved);
        boxed.stream().filter(definition -> !both.contains(definition)).forEach(both::add);
        return both;
    }

    private Collection<BeanDefinition<T>> definitionsOf(Argument<T> asked) {
        QualifierOverlay overlay = beanContext.findBean(QualifierOverlay.class).orElse(null);
        if (overlay == null || overlay.isEmpty()) {
            return beansAmong(beanContext.getBeanDefinitions(asked, CdiQualifiers.of(qualifiers)));
        }
        // a portable extension qualified a bean beyond what its definition says, which Micronaut's own
        // comparison of qualifiers does not see: the beans of the type are compared by the qualifiers each
        // has, as section 2.4.2 compares them
        CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
        List<BeanDefinition<T>> matching = new ArrayList<>();
        for (BeanDefinition<T> candidate : beansAmong(beanContext.getBeanDefinitions(asked))) {
            if (CdiAssignability.areQualifiersMatching(container.canonicalBean(candidate).qualifiers(), qualifiers)) {
                matching.add(candidate);
            }
        }
        return matching;
    }

    /**
     * The beans among the definitions a lookup resolved, one entry per bean.
     *
     * <p>The definition Micronaut compiles for an abstract class is not a bean: section 3.1.1 requires the class
     * of a managed bean to be concrete, however it is annotated. And the definition a proxy stands in front of
     * describes the same bean as the proxy, so it is dropped in favour of the proxy.</p>
     */
    private Collection<BeanDefinition<T>> beansAmong(Collection<BeanDefinition<T>> resolved) {
        java.util.Set<String> proxied = new java.util.HashSet<>();
        boolean abstractClasses = false;
        for (BeanDefinition<T> definition : resolved) {
            if (definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy) {
                proxied.add(proxy.getTargetDefinitionType().getName());
            }
            abstractClasses |= definition.isAbstract();
        }
        if (proxied.isEmpty() && !abstractClasses) {
            return resolved;
        }
        List<BeanDefinition<T>> beans = new ArrayList<>(resolved.size());
        for (BeanDefinition<T> definition : resolved) {
            if (definition.isAbstract()) {
                continue;
            }
            if (!(definition instanceof io.micronaut.inject.ProxyBeanDefinition<?>)
                && proxied.contains(definition.getClass().getName())) {
                continue;
            }
            beans.add(definition);
        }
        return beans;
    }

    /**
     * A handle on one of the beans a lookup resolved, which holds the instance and can destroy it.
     *
     * @param <T> The bean type
     */
    private static final class CdiHandle<T> implements Handle<T> {

        private final BeanContext beanContext;
        private final Argument<T> selected;
        private final BeanDefinition<T> definition;
        private final jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint lookupPoint;
        private final java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated;
        private io.micronaut.context.@Nullable BeanRegistration<T> registration;
        private @Nullable T instance;
        private boolean destroyed;

        private CdiHandle(BeanContext beanContext, Argument<T> selected, BeanDefinition<T> definition,
                          jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint lookupPoint,
                          java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated) {
            this.beanContext = beanContext;
            this.selected = selected;
            this.definition = definition;
            this.lookupPoint = lookupPoint;
            this.transientlyCreated = transientlyCreated;
        }

        @Override
        public T get() {
            if (destroyed) {
                throw new IllegalStateException("The handle on " + definition + " has been destroyed");
            }
            T resolved = instance;
            if (resolved == null) {
                if (CdiResolution.isDependent(definition)) {
                    // held as the registration that created it, which knows the dependents to destroy with it
                    io.micronaut.context.BeanRegistration<T> created =
                        createDependent(beanContext, selected, definition, lookupPoint);
                    registration = created;
                    // a dependent obtained through a handle is a dependent of the lookup like any other, and
                    // goes when the lookup goes — unless the handle destroys it first
                    transientlyCreated.add(created);
                    resolved = created.bean();
                } else {
                    resolved = resolve(beanContext, definition);
                }
                instance = resolved;
            }
            return resolved;
        }

        @Override
        public Bean<T> getBean() {
            return new CdiBean<>(beanContext, definition);
        }

        @Override
        public void destroy() {
            T resolved = instance;
            if (resolved == null) {
                // destroying a handle whose reference was never obtained is a no-op, the specification says —
                // and a no-op leaves the handle as it was, so a later get() still resolves lazily
                return;
            }
            if (!destroyed) {
                io.micronaut.context.BeanRegistration<T> created = registration;
                if (created != null) {
                    // taken off the lookup's list first: if it is no longer there the lookup already
                    // destroyed it, and destroying it twice would run its @PreDestroy twice
                    if (transientlyCreated.remove(created)) {
                        created.close();
                    }
                } else {
                    CdiInstance.destroyResolved(beanContext, definition, resolved);
                }
            }
            destroyed = true;
            instance = null;
            registration = null;
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
