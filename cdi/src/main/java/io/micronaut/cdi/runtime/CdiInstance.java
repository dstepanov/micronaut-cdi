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
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
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
public final class CdiInstance<T> implements Instance<T>, AutoCloseable {

    private final BeanContext beanContext;
    private final Argument<T> type;
    private final Annotation[] qualifiers;
    private final jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt;
    private final java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated;

    public CdiInstance(BeanContext beanContext, Argument<T> type, Annotation... qualifiers) {
        this(beanContext, null, type, qualifiers);
    }

    CdiInstance(BeanContext beanContext,
                jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt,
                Argument<T> type, Annotation... qualifiers) {
        // synchronized: the lookup of an injected Instance lives in whatever scope its owner does, and a
        // singleton owner uses it from every thread at once
        this(beanContext, injectedAt,
            java.util.Collections.synchronizedList(new java.util.ArrayList<>(2)), type, qualifiers);
    }

    private CdiInstance(BeanContext beanContext,
                        jakarta.enterprise.inject.spi.@org.jspecify.annotations.Nullable InjectionPoint injectedAt,
                        java.util.List<io.micronaut.context.BeanRegistration<?>> transientlyCreated,
                        Argument<T> type, Annotation... qualifiers) {
        this.beanContext = beanContext;
        this.injectedAt = injectedAt;
        // shared down every select: a dependent instance obtained through any narrowing of a lookup belongs
        // to the lookup itself, and is let go when the lookup is
        this.transientlyCreated = transientlyCreated;
        this.type = type;
        this.qualifiers = qualifiers;
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, type, and(qualifiers));
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, Argument.of(subtype), and(qualifiers));
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated,
            CdiTypes.argumentOf(subtype.getType()), and(qualifiers));
    }

    /**
     * This lookup narrowed to a compiled argument, generics and all: what an invoker's argument lookup is,
     * the argument being the method parameter as it was compiled.
     *
     * @param argument   The argument
     * @param qualifiers The qualifiers written on it
     * @param <U>        The argument's type
     * @return The narrowed lookup, sharing this one's dependent instances
     */
    <U> CdiInstance<U> selectArgument(Argument<U> argument, Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, transientlyCreated, argument, qualifiers);
    }

    private Annotation[] and(Annotation... more) {
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        for (Annotation qualifier : qualifiers) {
            seen.add(qualifier.annotationType());
        }
        for (Annotation qualifier : more) {
            Class<? extends Annotation> qualifierType = qualifier.annotationType();
            if (!ExtensionQualifiers.isQualifier(qualifierType)) {
                throw new IllegalArgumentException(qualifierType.getName() + " is not a qualifier");
            }
            if (!seen.add(qualifierType)
                && !qualifierType.isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                throw new IllegalArgumentException("The qualifier " + qualifierType.getName()
                    + " is given twice");
            }
        }
        Annotation[] all = new Annotation[qualifiers.length + more.length];
        System.arraycopy(qualifiers, 0, all, 0, qualifiers.length);
        System.arraycopy(more, 0, all, qualifiers.length, more.length);
        return all;
    }

    @Override
    public T get() {
        BeanDefinition<T> definition = one();
        if (CdiResolution.isDependent(definition)) {
            // a dependent instance obtained through this lookup belongs to the bean the lookup was injected
            // into, and is destroyed with it — which is when this lookup itself is closed. Section 2.5.2.5
            // gives it the lookup's own injection point as its metadata, which is left out for its creation
            jakarta.enterprise.inject.spi.InjectionPoint lookedUpAt = lookupPoint();
            if (lookedUpAt != null) {
                CurrentInjectionPoint.enter(lookedUpAt);
            }
            try {
                io.micronaut.context.BeanRegistration<T> registration =
                    beanContext.getBeanRegistration(askedAs(type, definition), only(definition));
                transientlyCreated.add(registration);
                return registration.bean();
            } catch (io.micronaut.context.exceptions.BeanCreationException e) {
                // what the bean's own code — or an interceptor around its construction — threw comes out as
                // it was thrown when it is unchecked (section 6.1.1)
                Throwable cause = CdiBean.deepestForeignCause(e);
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            } finally {
                if (lookedUpAt != null) {
                    CurrentInjectionPoint.leave();
                }
            }
        }
        return beanContext.getBean(definition);
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

    private io.micronaut.context.Qualifier<T> only(BeanDefinition<T> definition) {
        return new io.micronaut.context.Qualifier<T>() {
            @Override
            public <BT extends io.micronaut.inject.BeanType<T>> java.util.stream.Stream<BT> reduce(
                Class<T> beanType, java.util.stream.Stream<BT> candidates) {
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
            if (CdiResolution.isDependent(definition)) {
                // a dependent instance obtained through iteration belongs to the lookup the same way one
                // obtained through get() does, and is destroyed with it
                io.micronaut.context.BeanRegistration<T> registration =
                    beanContext.getBeanRegistration(askedAs(type, definition), only(definition));
                transientlyCreated.add(registration);
                beans.add(registration.bean());
            } else {
                beans.add(beanContext.getBean(definition));
            }
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
        for (BeanDefinition<T> definition : definitions()) {
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
            return injectedAt instanceof CdiInjectionPoint described ? described.viewedAs(type) : injectedAt;
        }
        if (jakarta.enterprise.inject.spi.InjectionPoint.class.isAssignableFrom(type.getType())) {
            // a programmatic lookup OF the injection point metadata asks about the point already current —
            // it is not itself a place a bean is being injected, and must not shadow the answer
            return null;
        }
        return new CdiInjectionPoint(null, type, null, null, false);
    }

    private Collection<BeanDefinition<T>> definitions() {
        Qualifier<T> qualifier = CdiQualifiers.of(qualifiers);
        Collection<BeanDefinition<T>> resolved = dedupProxies(beanContext.getBeanDefinitions(type, qualifier));
        Argument<T> counterpart = CdiTypes.counterpartOf(type);
        if (counterpart == null) {
            return resolved;
        }
        // a primitive and the class that boxes it are one bean type, and Micronaut keeps them apart
        Collection<BeanDefinition<T>> boxed = dedupProxies(beanContext.getBeanDefinitions(counterpart, qualifier));
        if (boxed.isEmpty()) {
            return resolved;
        }
        List<BeanDefinition<T>> both = new ArrayList<>(resolved);
        boxed.stream().filter(definition -> !both.contains(definition)).forEach(both::add);
        return both;
    }

    /**
     * Drops the definition a proxy stands in front of: the proxy and its target describe the same bean, and
     * the lookup answers with one entry per bean.
     */
    private Collection<BeanDefinition<T>> dedupProxies(Collection<BeanDefinition<T>> resolved) {
        java.util.Set<String> proxied = new java.util.HashSet<>();
        for (BeanDefinition<T> definition : resolved) {
            if (definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy) {
                proxied.add(proxy.getTargetDefinitionType().getName());
            }
        }
        if (proxied.isEmpty()) {
            return resolved;
        }
        List<BeanDefinition<T>> deduped = new ArrayList<>(resolved.size());
        for (BeanDefinition<T> definition : resolved) {
            if (!(definition instanceof io.micronaut.inject.ProxyBeanDefinition<?>)
                && proxied.contains(definition.getClass().getName())) {
                continue;
            }
            deduped.add(definition);
        }
        return deduped;
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
                    if (lookupPoint != null) {
                        CurrentInjectionPoint.enter(lookupPoint);
                    }
                    io.micronaut.context.BeanRegistration<T> created;
                    try {
                        created = beanContext.getBeanRegistration(askedAs(selected, definition), onlyThis());
                    } finally {
                        if (lookupPoint != null) {
                            CurrentInjectionPoint.leave();
                        }
                    }
                    registration = created;
                    // a dependent obtained through a handle is a dependent of the lookup like any other, and
                    // goes when the lookup goes — unless the handle destroys it first
                    transientlyCreated.add(created);
                    resolved = created.bean();
                } else {
                    resolved = beanContext.getBean(definition);
                }
                instance = resolved;
            }
            return resolved;
        }

        private io.micronaut.context.Qualifier<T> onlyThis() {
            return new io.micronaut.context.Qualifier<T>() {
                @Override
                public <BT extends io.micronaut.inject.BeanType<T>> java.util.stream.Stream<BT> reduce(
                    Class<T> beanType, java.util.stream.Stream<BT> candidates) {
                    return candidates.filter(candidate -> candidate.equals(definition));
                }
            };
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
