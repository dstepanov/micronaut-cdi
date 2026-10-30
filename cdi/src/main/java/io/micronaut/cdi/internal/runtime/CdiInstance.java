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

import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.exceptions.BeanCreationException;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
public final class CdiInstance<T> implements MicronautInstance<T>, AutoCloseable {

    private final BeanContext beanContext;
    private final Argument<T> type;
    private final List<CdiQualifier> qualifiers;
    private final @Nullable InjectionPoint injectedAt;
    /**
     * The segment of the resolution path the lookup was injected at, which a dependent instance the lookup creates
     * is created under, as the lookup narrowed it ({@link LookupSegment}): the instance reads its injection point off
     * the path of its own creation. A lookup injected nowhere has none.
     */
    private final BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment;
    /**
     * What the lookup creates of dependent instances, shared by every lookup selected from it: the instances of
     * the bean it was injected into, or its own.
     */
    private final OwnedDependents dependents;
    /**
     * The Micronaut qualifier a program narrowed the lookup by, beside the qualifiers of the specification: a
     * predicate over definitions, which has no annotation to report.
     */
    private final @Nullable Qualifier<?> narrowedBy;

    /**
     * A lookup injected nowhere, whose dependent instances are its own and go when it is closed.
     *
     * @param beanContext The context the lookup resolves in
     * @param type        The type looked up
     * @param qualifiers  The qualifiers
     */
    public CdiInstance(BeanContext beanContext, Argument<T> type, Annotation... qualifiers) {
        this(beanContext, null, null, OwnedDependents.of(beanContext), type,
            CdiQualifier.ofInstances(CompiledAnnotationTypes.of(beanContext), qualifiers));
    }

    CdiInstance(BeanContext beanContext,
                @Nullable InjectionPoint injectedAt,
                BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment,
                OwnedDependents dependents, Argument<T> type, List<CdiQualifier> qualifiers) {
        this(beanContext, injectedAt, injectionSegment, dependents, null, type, qualifiers);
    }

    private CdiInstance(BeanContext beanContext,
                        @Nullable InjectionPoint injectedAt,
                        BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment,
                        OwnedDependents dependents,
                        @Nullable Qualifier<?> narrowedBy,
                        Argument<T> type, List<CdiQualifier> qualifiers) {
        this.beanContext = beanContext;
        this.injectedAt = injectedAt;
        this.injectionSegment = injectionSegment;
        this.dependents = dependents;
        this.narrowedBy = narrowedBy;
        this.type = type;
        this.qualifiers = qualifiers;
    }

    /**
     * A lookup of every bean whose dependent instances belong to the owner given, with nothing selected yet: what
     * the creation and the disposal of a synthetic bean are handed, creating on behalf of the bean.
     *
     * @param beanContext The context the lookup resolves in
     * @param dependents  Who the dependent instances it creates belong to
     * @return The lookup
     */
    public static CdiInstance<Object> ownedBy(BeanContext beanContext, OwnedDependents dependents) {
        return new CdiInstance<>(beanContext, null, null, dependents, Argument.OBJECT_ARGUMENT, List.of());
    }

    @Override
    public MicronautInstance<T> select(Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, type,
            and(CdiQualifier.ofInstances(CompiledAnnotationTypes.of(beanContext), qualifiers)));
    }

    @Override
    public <U extends T> MicronautInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, Argument.of(subtype),
            and(CdiQualifier.ofInstances(CompiledAnnotationTypes.of(beanContext), qualifiers)));
    }

    @Override
    public <U extends T> MicronautInstance<U> select(TypeLiteral<U> subtype,
                                                     Annotation... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy,
            CdiTypes.argumentOf(subtype.getType()), and(CdiQualifier.ofInstances(CompiledAnnotationTypes.of(beanContext), qualifiers)));
    }

    @Override
    public MicronautInstance<T> select(AnnotationValue<?> qualifier,
                                       AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, type, and(valuesOf(CompiledAnnotationTypes.of(beanContext), qualifier, qualifiers)));
    }

    @Override
    public <U extends T> MicronautInstance<U> select(
        Class<U> subtype, AnnotationValue<?> qualifier,
        AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, Argument.of(subtype),
            and(valuesOf(CompiledAnnotationTypes.of(beanContext), qualifier, qualifiers)));
    }

    @Override
    public <U extends T> MicronautInstance<U> select(
        Argument<U> subtype, AnnotationValue<?>... qualifiers) {
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, subtype,
            and(valuesOf(CompiledAnnotationTypes.of(beanContext), null, qualifiers)));
    }

    @Override
    public <U extends T> MicronautInstance<U> select(Argument<U> subtype,
                                                     Qualifier<U> qualifier) {
        // a predicate over definitions rather than an annotation: none of the rules of the specification for a
        // qualifier given to select applies to it
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy(qualifier), subtype,
            qualifiers);
    }

    @Override
    public <U extends T> MicronautInstance<U> select(Class<U> subtype,
                                                     Qualifier<U> qualifier) {
        return select(Argument.of(subtype), qualifier);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Qualifier<?> narrowedBy(Qualifier<?> qualifier) {
        Qualifier<?> already = narrowedBy;
        return already == null ? qualifier : Qualifiers.byQualifiers((Qualifier) already,
            (Qualifier) qualifier);
    }

    /**
     * The qualifiers of the specification the lookup requires: the ones selected, or {@code Any} where none was
     * and a Micronaut qualifier narrows the lookup, which is a qualifier selected and so no longer leaves the
     * lookup requiring the default one.
     */
    private List<CdiQualifier> required() {
        return qualifiers.isEmpty() && narrowedBy != null ? List.of(CdiQualifier.ANY) : qualifiers;
    }

    static List<CdiQualifier> valuesOf(CompiledAnnotationTypes types, @Nullable AnnotationValue<?> first,
                                       AnnotationValue<?>... more) {
        List<CdiQualifier> all = new ArrayList<>(more.length + 1);
        if (first != null) {
            all.add(CdiQualifier.ofValue(types, first));
        }
        for (AnnotationValue<?> value : more) {
            all.add(CdiQualifier.ofValue(types, value));
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
        return new CdiInstance<>(beanContext, injectedAt, injectionSegment, dependents, narrowedBy, argument, qualifiers);
    }

    private List<CdiQualifier> and(List<CdiQualifier> more) {
        CdiQualifier.requireWellFormed(CompiledAnnotationTypes.of(beanContext), qualifiers, more);
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
        // into, and is destroyed with it, or to the lookup, and is destroyed as it is closed. Section 2.5.2.5
        // gives it the lookup's own injection point as its metadata, which its creation's path says
        return createDependent(dependents, beanContext, type, definition, lookupPoint(), injectionSegment).bean();
    }

    /**
     * Creates a dependent instance for a lookup, however the lookup hands it out - {@code get()}, iterating, or
     * a handle - so that what its creation throws comes out the same from each.
     *
     * @param dependents  Who the instance belongs to
     * @param beanContext The bean context
     * @param selected    The type the lookup selected
     * @param definition  The bean
     * @param lookedUpAt  The injection point of the lookup, or {@code null} for a lookup of the point already current
     * @param injectionSegment The segment of the resolution path the lookup was injected at, or {@code null} for a
     *                    lookup injected nowhere
     * @param <U>         The bean type
     * @return The registration that created the instance, which knows the dependents to destroy with it
     */
    static <U> BeanRegistration<U> createDependent(
        OwnedDependents dependents, BeanContext beanContext, Argument<U> selected, BeanDefinition<U> definition,
        @Nullable InjectionPoint lookedUpAt,
        BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment) {
        try {
            // the definition is the one the lookup chose: resolved as it is, with no candidate lookup of its own. One
            // created for the point already current - the injection point metadata - is resolved within the creation
            // the lookup is made in, where there is one, whose path says what that point is. Any other is created on a
            // path that says what the lookup's injection point is
            Argument<U> asked = askedAs(beanContext, selected, definition);
            return lookedUpAt == null
                ? dependents.registrationAtCurrentPoint(definition, asked)
                : dependents.registrationAt(injectionSegment, lookedUpAt, definition, asked);
        } catch (BeanCreationException e) {
            // what the bean's own code — or an interceptor around its construction — threw comes out as
            // it was thrown when it is unchecked, and wrapped when it is checked (section 6.1.1)
            throw CdiBean.translated(e);
        }
    }

    /**
     * Resolves the instance of a bean that has a scope, which may be what creates it: what its creation throws
     * comes out as what a dependent creation throws does.
     */
    static <U> U resolve(BeanContext beanContext, BeanDefinition<U> definition) {
        try {
            return beanContext.getBean(definition);
        } catch (BeanCreationException e) {
            throw CdiBean.translated(e);
        }
    }

    /**
     * The argument a dependent creation is asked with: the type the lookup selected, generics included, so
     * that a parameterized built-in — an {@code Event<X>}, an {@code Instance<X>} — reads what it is for, and
     * a bean resolvable only by its declared types is asked by one of them. The definition's own type serves
     * only where the selected one does not name the bean — a primitive selected where the boxed type is the
     * bean's, say.
     *
     * @param beanContext The context of the container asking, whose generic hierarchies an array is read from
     * @param selected    The type the lookup selected
     * @param definition  The definition of the bean
     * @param <U>         The bean type
     * @return The argument
     */
    static <U> Argument<U> askedAs(BeanContext beanContext, Argument<U> selected, BeanDefinition<U> definition) {
        if (CdiTypes.isArray(selected)) {
            // The producer's compiled definition may expose only the raw array argument. The lookup already
            // matched its recorded CDI types, so preserve the component's parameters for candidate resolution.
            return selected;
        }
        if (CdiTypes.isArray(definition.asArgument())) {
            // A qualified Object lookup can select an array producer too. Resolve it by its recorded array
            // type rather than the erased array argument of the generated definition.
            RecordedTypeIndex index = RecordedTypeIndex.of(beanContext);
            for (Argument<?> type : CdiBean.typesOf(index, definition, definition.getBeanType())) {
                if (CdiTypes.isArray(type)) {
                    @SuppressWarnings("unchecked") Argument<U> array = (Argument<U>) type;
                    return array;
                }
            }
        }
        if (definition.isCandidateBean(selected)) {
            if (selected.getTypeParameters().length > 0) {
                // a parameterized selection carries what a parameterized built-in reads: what an Event<X> is of
                return selected;
            }
            Set<Class<?>> exposed = definition.getExposedTypes();
            if (!exposed.isEmpty() && !exposed.contains(definition.asArgument().getType())) {
                // the definition does not expose its own type — a synthetic bean resolvable only by what it
                // declared — so it can only be asked by the selected one
                return selected;
            }
        }
        return definition.asArgument();
    }

    /**
     * Lets go of every dependent instance this lookup created as its own, which happens when the bean the lookup
     * belongs to is destroyed, and when a synthetic bean's creation or disposal function is done with the lookup it
     * was handed (section 2.10.5). What it created for the bean it was injected into goes with that bean.
     */
    @Override
    public void close() {
        dependents.close();
    }

    /**
     * The one definition the lookup resolves to, narrowed the way section 2.4.2 narrows several candidates.
     */
    private BeanDefinition<T> one() {
        List<BeanDefinition<T>> candidates = CdiResolution.narrowToOne(definitions(), type, narrowedBy, beanContext);
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
        return CdiResolution.narrowToOne(definitions(), type, narrowedBy, beanContext).size() > 1;
    }

    @Override
    public void destroy(T instance) {
        // the instance this lookup created is destroyed through the registration that created it, which is
        // what knows the dependents that were created along with it
        CreatedBean<?> tracked = dependents.registrationOf(instance);
        if (tracked != null) {
            dependents.destroy(tracked);
            return;
        }
        Collection<BeanDefinition<T>> definitions = definitions();
        // the bean the instance is an instance of is the one whose registration holds it. The class of the
        // instance does not say: two producers of one class are two beans, each with a disposer of its own
        BeanDefinition<T> holding = beanContext.findBeanRegistration(instance)
            .map(BeanRegistration::getBeanDefinition)
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
        if (CdiResolution.isNormalScoped(definition.getAnnotationMetadata())) {
            CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
            CdiBean<?> bean = container.canonicalBean(definition);
            Context context = container.getContext(bean.getScope());
            if (context instanceof AlterableContext alterable) {
                alterable.destroy(bean);
                return;
            }
        }
        destroy(beanContext, definition, instance);
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
        return new CdiHandle<>(beanContext, type, one(), lookupPoint(), injectionSegment, dependents);
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        // each iteration is a fresh set of handles, the specification says: a handle resolved or destroyed on
        // one pass must not be what a second pass hands out
        return () -> CdiResolution.narrow(definitions()).stream()
            .map(definition -> (Handle<T>) new CdiHandle<>(beanContext, type, definition, lookupPoint(),
                injectionSegment, dependents))
            .iterator();
    }

    /**
     * The injection point a dependent instance obtained through this lookup gets as its metadata: the point
     * the lookup was injected into where there is one (section 2.5.2.5), and otherwise the lookup itself,
     * described with the type it was selected as.
     */
    private @Nullable InjectionPoint lookupPoint() {
        if (injectedAt != null) {
            return injectedAt instanceof CdiInjectionPoint described
                ? described.viewedAs(type, required())
                : injectedAt;
        }
        if (InjectionPoint.class.isAssignableFrom(type.getType())) {
            // a programmatic lookup OF the injection point metadata asks about the point already current —
            // it is not itself a place a bean is being injected, and must not shadow the answer
            return null;
        }
        // a lookup that was injected nowhere - the bean container's, CDI.current(), the SE container - stands
        // for itself, and requires what it selected: the type, and every qualifier of every select, with the
        // members each was handed over with (section 2.4.5.7)
        return new CdiInjectionPoint(beanContext, null, type, null).viewedAs(type, required());
    }

    private Collection<BeanDefinition<T>> definitions() {
        // the beans of the type and the qualifiers as the container selects them for an injection point and the
        // bean manager alike - a primitive and the class that boxes it as one type, a qualifier a portable
        // extension added among the ones a bean has - and the Micronaut qualifier a program narrowed the lookup by
        return beanContext.getBean(CdiBeanContainer.class).definitionsOf(type, required(), narrowedBy);
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
        private final @Nullable InjectionPoint lookupPoint;
        private final BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment;
        private final OwnedDependents dependents;
        private @Nullable BeanRegistration<T> registration;
        private @Nullable T instance;
        /**
         * Whether the reference was obtained: null is a valid product of a dependent producer, so the instance
         * alone cannot say.
         */
        private boolean obtained;
        private boolean destroyed;

        private CdiHandle(BeanContext beanContext, Argument<T> selected, BeanDefinition<T> definition,
                          @Nullable InjectionPoint lookupPoint,
                          BeanResolutionContext.@Nullable Segment<?, ?> injectionSegment,
                          OwnedDependents dependents) {
            this.beanContext = beanContext;
            this.selected = selected;
            this.definition = definition;
            this.lookupPoint = lookupPoint;
            this.injectionSegment = injectionSegment;
            this.dependents = dependents;
        }

        @Override
        public @Nullable T get() {
            if (destroyed) {
                throw new IllegalStateException("The handle on " + definition + " has been destroyed");
            }
            T resolved = instance;
            if (!obtained) {
                if (CdiResolution.isDependent(definition)) {
                    // held as the registration that created it, which knows the dependents to destroy with it
                    // a dependent obtained through a handle is a dependent of the lookup like any other, and
                    // goes when the lookup goes — unless the handle destroys it first
                    BeanRegistration<T> created =
                        createDependent(dependents, beanContext, selected, definition, lookupPoint,
                            injectionSegment);
                    registration = created;
                    resolved = created.bean();
                } else {
                    resolved = resolve(beanContext, definition);
                }
                instance = resolved;
                obtained = true;
            }
            return resolved;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Bean<T> getBean() {
            // the container's own bean of the definition, the one it answers with
            return (Bean<T>) beanContext.getBean(CdiBeanContainer.class).canonicalBean(definition);
        }

        @Override
        public void destroy() {
            if (!obtained) {
                // destroying a handle whose reference was never obtained is a no-op, the specification says —
                // and a no-op leaves the handle as it was, so a later get() still resolves lazily
                return;
            }
            T resolved = instance;
            if (!destroyed) {
                BeanRegistration<T> created = registration;
                if (created != null) {
                    // destroyed only where the lookup still has it: if it does not the lookup destroyed it already,
                    // and destroying it twice would run its @PreDestroy twice. A null product is destroyed too:
                    // what was injected into its producer belongs to it (section 6.4.1)
                    dependents.destroy(created);
                } else if (resolved != null) {
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
