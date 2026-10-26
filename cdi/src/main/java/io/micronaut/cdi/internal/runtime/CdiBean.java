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

import io.micronaut.cdi.internal.metadata.CdiBeanTypes;
import io.micronaut.cdi.internal.metadata.CdiName;
import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.core.type.Argument;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.spi.InjectionSite;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A bean of the specification, read from the Micronaut bean definition it was compiled into.
 *
 * <p>The two describe the same bean, and mostly in the same terms: a type, a set of qualifiers, a scope, a name.
 * What this adds is the reading of the ones that do not line up. The bean types of the specification are the
 * whole of the class hierarchy rather than the one type Micronaut names, unless the bean narrowed them; and the
 * scope is the one the bean was written with, which was recorded by {@link CdiScope} when it was read as a
 * Micronaut one.</p>
 *
 * <p>A subclass stands for the same bean, equal to it, and changes only how an instance is created: what the
 * context of a scope an extension provides is handed to create the instance through the scope.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public class CdiBean<T> implements Bean<T> {

    private final BeanContext beanContext;
    private final BeanDefinition<T> definition;
    private volatile java.util.@Nullable List<CdiQualifier> qualifiers;
    private volatile java.util.@Nullable Optional<QualifierOverlay> overlay;
    private volatile java.util.@Nullable List<Argument<?>> types;

    public CdiBean(BeanContext beanContext, BeanDefinition<T> definition) {
        this.beanContext = beanContext;
        this.definition = definition;
    }

    /**
     * The Micronaut bean definition this was read from.
     *
     * @return The definition
     */
    public BeanDefinition<T> definition() {
        return definition;
    }

    @Override
    public Class<?> getBeanClass() {
        // the bean class of a produced bean is the class that declares its producer (the specification's
        // Bean.getBeanClass), not the class of what it produces
        Class<?> declaring = definition.getAnnotationMetadata()
            .classValue(CdiProducer.class, "declaringType").orElse(null);
        return declaring != null ? declaring : beanClass();
    }

    /**
     * The definition of the class itself, which for a bean in a normal scope is the proxy's target.
     */
    private BeanDefinition<T> targetDefinition() {
        return beanContext.findProxyTargetBeanDefinition(definition).orElse(definition);
    }

    /**
     * The class the bean was written as.
     *
     * <p>A bean in a normal scope is reached through a client proxy, and the definition Micronaut resolves for it
     * is the one of the proxy: its type is the generated subclass rather than the class the author wrote. The
     * specification reports the class that was written, so the proxy is looked through here.</p>
     */
    private Class<?> beanClass() {
        if (definition instanceof ProxyBeanDefinition<T> proxy) {
            return proxy.getTargetType();
        }
        return definition.getBeanType();
    }

    @Override
    public Set<Type> getTypes() {
        Set<Type> types = new LinkedHashSet<>();
        for (Argument<?> type : types()) {
            types.add(type.asType());
        }
        return types;
    }

    /**
     * The bean types of the bean as resolution compares them.
     *
     * @return The bean types
     */
    public java.util.List<Argument<?>> types() {
        // worked out once for the context, which Micronaut's own lookups of the bean compare too
        java.util.List<Argument<?>> resolved = types;
        if (resolved == null) {
            resolved = RecordedTypeIndex.of(beanContext).beanTypesOf(definition);
            types = resolved;
        }
        return resolved;
    }

    /**
     * The type closure the processor recorded for the bean, where it recorded one and every class of it can
     * be referred to from the definition.
     */
    private static java.util.@Nullable List<Argument<?>> recordedClosureOf(BeanDefinition<?> definition) {
        java.util.List<io.micronaut.core.annotation.AnnotationValue<Annotation>> records = definition
            .getAnnotationMetadata().findAnnotation(CdiBeanTypes.class)
            .map(types -> types.getAnnotations("value")).orElse(java.util.List.of());
        if (records.isEmpty()) {
            return null;
        }
        java.util.List<Argument<?>> closure = new java.util.ArrayList<>(records.size());
        for (io.micronaut.core.annotation.AnnotationValue<Annotation> record : records) {
            Argument<?> type = RecordedTypes.find(record);
            if (type == null) {
                return null;
            }
            closure.add(type);
        }
        return closure;
    }

    /**
     * The type a producer declared, as the definition of its bean holds it. The definition of the client proxy of a
     * produced bean in a normal scope is of the subclass Micronaut generates, and holds the type arguments of the
     * type the bean was produced as under that type.
     */
    private static Argument<?> producedTypeOf(BeanDefinition<?> definition) {
        Argument<?> declared = definition.getDeclaredBeanType();
        if (definition instanceof ProxyBeanDefinition<?> proxy && proxy.getTargetType() != declared.getType()) {
            Class<?> target = proxy.getTargetType();
            return Argument.of(target, (String) null,
                definition.getTypeArguments(target).toArray(Argument.ZERO_ARGUMENTS));
        }
        return declared;
    }

    /**
     * Whether a factory method or field produces the bean of the definition: Micronaut names the class that
     * declares what creates the bean, which is the bean class itself for a bean created by its constructor.
     */
    private static boolean isFactoryProduct(BeanDefinition<?> definition) {
        return definition.getDeclaringType().filter(declaring -> declaring != definition.getBeanType()).isPresent();
    }

    /**
     * The bean types of the bean the given definition describes, without an instance of this class around.
     *
     * @param index      The generic hierarchies the processor recorded, or {@code null}
     * @param definition The definition
     * @param beanClass  The class of the bean, with a proxy's target already resolved
     * @return The bean types
     */
    static java.util.List<Argument<?>> typesOf(@Nullable RecordedTypeIndex index, BeanDefinition<?> definition,
                                               Class<?> beanClass) {
        if (definition instanceof io.micronaut.inject.provider.AbstractProviderDefinition<?>) {
            // Core's infrastructure providers implement Iterable as a lookup convenience. Their helper
            // interfaces are not application beans satisfying an ordinary CDI collection injection.
            return java.util.List.of(definition.asArgument(), Argument.OBJECT_ARGUMENT);
        }
        java.util.List<Argument<?>> types = new java.util.ArrayList<>();
        // the types a bean narrowed itself to are the ones it named with Typed, which is asked for rather than
        // Micronaut's own set of exposed types: those are what Micronaut resolves the bean by, and it exposes an
        // array by its component type as well, which is not a bean type of the array
        Class<?>[] narrowed = definition.getAnnotationMetadata()
            .classValues("jakarta.enterprise.inject.Typed");
        java.util.List<Argument<?>> closure = new java.util.ArrayList<>();
        boolean produced = definition.getAnnotationMetadata().hasAnnotation(CdiProducer.class);
        java.util.List<Argument<?>> recorded = index != null
            && index.hasCompleteRecord(produced ? producedTypeOf(definition).getType() : beanClass)
            ? null : recordedClosureOf(definition);
        if (recorded != null) {
            // the closure the processor recorded of the bean as it compiled it, where the type hierarchy record of
            // its class left a supertype out, or there is none
            closure.addAll(recorded);
        } else if (produced) {
            // a produced bean is a bean of the type the producer declared — with the arguments it was written
            // with, its variables kept (section 3.3.2), or raw if it was written raw — not of the produced
            // class's own declaration
            for (Argument<?> type : CdiTypes.beanTypeClosureOf(index, producedTypeOf(definition))) {
                closure.add(CdiTypes.withErasedBounds(type));
            }
        } else {
            // the bean types of a bean are every class and interface its own type is assignable to, with the
            // parameters a generic type was written with: a generic class is a bean of its parameterized form
            // rather than of its erasure. A bean a factory method or field of Micronaut's own produces is, like a
            // producer's, a bean of the type the method declares, with the arguments it was written with
            if (CdiTypes.knowsClosureOf(index, beanClass)) {
                java.util.List<Argument<?>> closureOfBean = isFactoryProduct(definition)
                    ? CdiTypes.beanTypeClosureOf(index, producedTypeOf(definition))
                    : CdiTypes.beanTypeClosureOf(index, beanClass);
                for (Argument<?> type : closureOfBean) {
                    closure.add(CdiTypes.withErasedBounds(type));
                }
            } else {
                // a bean compiled without this processor, of which nothing was recorded: it is resolvable by
                // its class and by the raw types Micronaut exposes it as
                closure.add(Argument.of(beanClass));
                for (Class<?> exposed : definition.getExposedTypes()) {
                    closure.add(Argument.of(exposed));
                }
            }
            Set<Class<?>> exposed = definition.getExposedTypes();
            if (!exposed.isEmpty() && !exposed.contains(beanClass)) {
                // a definition that does not expose its own class - a synthetic bean of exactly the types it
                // declared, a bean of Micronaut's own typed to some of its types - is a bean of those alone
                closure.removeIf(type -> {
                    Class<?> raw = CdiTypes.classOf(type);
                    return raw == null || !exposed.contains(raw);
                });
                for (Class<?> type : exposed) {
                    if (closure.stream().noneMatch(known -> CdiTypes.classOf(known) == type)) {
                        closure.add(Argument.of(type));
                    }
                }
            }
        }
        if (definition.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Typed")) {
            // the types the bean named with Typed keep the parameters the closure gives them: an Emu typed
            // FlightlessBird is a bean of FlightlessBird<Australian>, which is what it extends
            Set<Class<?>> kept = new LinkedHashSet<>(java.util.Arrays.asList(narrowed));
            for (Argument<?> candidate : closure) {
                Class<?> raw = CdiTypes.classOf(candidate);
                if (raw != null && kept.contains(raw)) {
                    CdiTypes.addDistinct(types, candidate);
                }
            }
        } else {
            for (Argument<?> candidate : closure) {
                CdiTypes.addDistinct(types, candidate);
            }
        }
        // a parameterized type containing a wildcard is not a legal bean type (section 2.2.1): a supertype
        // written that way is simply not among the types the bean can be resolved by
        types.removeIf(type -> !CdiAssignability.isLegalBeanType(type));
        // every bean has Object among its types, whatever it narrowed them to
        CdiTypes.addDistinct(types, Argument.OBJECT_ARGUMENT);
        return types;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return CdiQualifier.instances(beanContext, qualifiers());
    }

    /**
     * The qualifiers of the bean as resolution compares them: the ones it was compiled with, {@code Any}, which
     * every bean has, and what a portable extension added to the bean class, read as they are asked for, so that
     * an addition made after an earlier lookup is seen by the next one.
     *
     * @return The qualifiers
     */
    final java.util.List<CdiQualifier> qualifiers() {
        java.util.List<CdiQualifier> compiled = qualifiers;
        if (compiled == null) {
            java.util.List<CdiQualifier> all = new java.util.ArrayList<>(3);
            all.add(CdiQualifier.ANY);
            for (CdiQualifier declared : CdiQualifier.declared(definition.getAnnotationMetadata())) {
                if (!declared.isAny()) {
                    all.add(declared);
                }
            }
            compiled = java.util.List.copyOf(all);
            qualifiers = compiled;
        }
        java.util.Optional<QualifierOverlay> found = overlay;
        if (found == null) {
            // a bean of the context, the same for as long as it runs
            found = beanContext.findBean(QualifierOverlay.class);
            overlay = found;
        }
        return found.isPresent() ? found.get().apply(definition, compiled) : compiled;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        Class<? extends Annotation> written = metadata.stringValue(CdiScope.class)
            .map(name -> annotationNamed(metadata, name))
            .orElse(null);
        if (written != null) {
            return written;
        }
        // a bean that was not written with a scope of the specification is one of Micronaut's own, and the two
        // scopes it can be in are the ones the specification also has
        return definition.isSingleton() ? Singleton.class : Dependent.class;
    }

    /**
     * The annotation class of the given name: a scope of the specification, which is known by name, or an
     * annotation the bean was compiled with, whose class the compiled metadata refers to.
     */
    private static @Nullable Class<? extends Annotation> annotationNamed(AnnotationMetadata metadata, String name) {
        return switch (name) {
            case "jakarta.enterprise.context.Dependent" -> Dependent.class;
            case "jakarta.enterprise.context.ApplicationScoped" -> jakarta.enterprise.context.ApplicationScoped.class;
            case "jakarta.enterprise.context.RequestScoped" -> jakarta.enterprise.context.RequestScoped.class;
            case "jakarta.enterprise.context.SessionScoped" -> jakarta.enterprise.context.SessionScoped.class;
            case "jakarta.enterprise.context.ConversationScoped" ->
                jakarta.enterprise.context.ConversationScoped.class;
            case "jakarta.inject.Singleton" -> Singleton.class;
            default -> metadata.getAnnotationType(name).orElse(null);
        };
    }

    @Override
    public @Nullable String getName() {
        return nameOf(definition);
    }

    /**
     * The name of the bean of a definition (section 2.6), as it was recorded.
     *
     * @param definition The definition
     * @return The name, or {@code null} for a bean that has none
     */
    static @Nullable String nameOf(BeanDefinition<?> definition) {
        // the stereotype-given name first: where one was recorded, the jakarta annotation beside it is only
        // the default Micronaut materialized, spelled by Micronaut's rules rather than the specification's
        io.micronaut.core.annotation.AnnotationMetadata metadata = definition.getAnnotationMetadata();
        return metadata.stringValue(CdiName.class)
            .or(() -> metadata.stringValue("jakarta.inject.Named"))
            .orElse(null);
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        Set<Class<? extends Annotation>> stereotypes = new LinkedHashSet<>();
        for (String name : definition.getAnnotationMetadata()
            .getAnnotationNamesByStereotype("jakarta.enterprise.inject.Stereotype")) {
            Class<? extends Annotation> stereotype = annotationNamed(definition.getAnnotationMetadata(), name);
            if (stereotype != null) {
                stereotypes.add(stereotype);
            }
        }
        return stereotypes;
    }

    @Override
    public boolean isAlternative() {
        return definition.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Alternative")
            || definition.getAnnotationMetadata().hasStereotype("jakarta.enterprise.inject.Alternative");
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        Set<InjectionPoint> points = new LinkedHashSet<>();
        Class<?> declaring = beanClass();
        // a bean in a normal scope resolves to its proxy definition, whose constructor and members are the
        // proxy's; the injection points the specification describes are the class's own
        BeanDefinition<T> described = targetDefinition();
        io.micronaut.inject.ConstructorInjectionPoint<T> constructor = described.getConstructor();
        io.micronaut.core.annotation.AnnotationValue<CdiProducer> producer =
            described.getAnnotationMetadata().getAnnotation(CdiProducer.class);
        if (producer == null) {
            io.micronaut.core.type.Argument<?>[] arguments = constructor.getArguments();
            boolean proxy = CdiResolution.isProxyDefinition(described);
            for (io.micronaut.core.type.Argument<?> argument : arguments) {
                if (proxy && CdiResolution.isGeneratedConstructorParameter(argument)) {
                    // what the container itself passes a generated constructor is not an injection point
                    continue;
                }
                points.add(new CdiInjectionPoint(beanContext, this, argument, CdiInjectionPoint.parameterSite(declaring,
                    InjectionSite.CONSTRUCTOR, arguments, argument, proxy)));
            }
        } else if (!producer.booleanValue("field").orElse(false)) {
            // section 2.2.2.2: all producer method parameters are injection points. The producer method is what
            // Micronaut constructs the bean with, and its parameters are the ones of the method the producer
            // names; a producer field has none
            String member = producer.stringValue("member").orElse(InjectionSite.CONSTRUCTOR);
            io.micronaut.core.type.Argument<?>[] arguments = constructor.getArguments();
            for (io.micronaut.core.type.Argument<?> argument : arguments) {
                points.add(new CdiInjectionPoint(beanContext, this, argument,
                    CdiInjectionPoint.parameterSite(getBeanClass(), member, arguments, argument, false)));
            }
        }
        for (io.micronaut.inject.FieldInjectionPoint<T, ?> field : described.getInjectedFields()) {
            points.add(new CdiInjectionPoint(beanContext, this, field.asArgument(), InjectionSite.ofField(declaring,
                field.getName())));
        }
        for (io.micronaut.inject.MethodInjectionPoint<T, ?> method : described.getInjectedMethods()) {
            if (method.isPostConstructMethod() || method.isPreDestroyMethod()) {
                continue;
            }
            io.micronaut.core.type.Argument<?>[] arguments = method.getArguments();
            for (io.micronaut.core.type.Argument<?> argument : arguments) {
                points.add(new CdiInjectionPoint(beanContext, this, argument,
                    CdiInjectionPoint.methodSite(declaring, method, argument)));
            }
        }
        return points;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        try {
            OwnedDependents owner = creationalContext instanceof CdiCreationalContext<T> tracking
                ? tracking.created() : null;
            if (isDependent() && owner != null) {
                // a dependent instance belongs to whoever asked for it, and what was created along with it
                // belongs to it: releasing the creational context destroys both
                return owner.registration(definition, definition.asArgument()).bean();
            }
            // section 2.5.1: create() creates a new contextual instance. That a scope has one instance is the
            // business of its context, which creates it once and hands the same one out - scopedInstance()
            io.micronaut.context.scope.CreatedBean<T> created = createOutsideOfScope(owner);
            if (created == null) {
                return scopedInstance();
            }
            return created.bean();
        } catch (io.micronaut.context.exceptions.BeanCreationException e) {
            // section 6.1.1: what the bean itself threw comes out as it was thrown if it is unchecked, and
            // wrapped in a CreationException if it is checked
            throw translated(e);
        }
    }

    /**
     * The instance of the bean its scope holds, created where the scope holds none yet: what a context hands out,
     * and what a contextual reference resolves to.
     *
     * @return The instance of the scope
     */
    T scopedInstance() {
        try {
            if (isNormalScoped()) {
                // the contextual instance the scope holds, not the client proxy in front of it: a producer that
                // misbehaves — returning null, say — is heard from here
                return beanContext.getProxyTargetBean(targetArgument(), definition.getDeclaredQualifier());
            }
            return beanContext.getBean(definition);
        } catch (io.micronaut.context.exceptions.BeanCreationException e) {
            throw translated(e);
        }
    }

    @SuppressWarnings("unchecked")
    private io.micronaut.core.type.Argument<T> targetArgument() {
        return definition instanceof io.micronaut.inject.ProxyBeanDefinition<T> proxy
            ? (io.micronaut.core.type.Argument<T>) io.micronaut.core.type.Argument.of(
                proxy.getTargetType(), definition.asArgument().getTypeParameters())
            : definition.asArgument();
    }

    /**
     * Creates a fresh scoped instance and retains the complete dependency tree for its owner.
     *
     * @param owner What the creational context the instance is created in owns, or {@code null} for a creational
     *              context of another kind, which owns nothing
     * @return The fresh instance and its lifecycle, or {@code null} for a dependent bean
     */
    private io.micronaut.context.scope.@org.jspecify.annotations.Nullable CreatedBean<T> createOutsideOfScope(
        @org.jspecify.annotations.Nullable OwnedDependents owner) {
        if (isNormalScoped() || definition.isSingleton()) {
            return beanContext.getBean(io.micronaut.cdi.internal.context.RequestScope.class)
                .duringCreation(() -> owner != null
                    ? owner.fresh(targetDefinition()) : beanContext.createBeanRegistration(targetDefinition()));
        }
        return null;
    }

    /**
     * What a failure to create a bean comes out as (section 6.1.1): the exception the bean's own code threw, as
     * it was thrown when it is unchecked and wrapped in a {@link jakarta.enterprise.inject.CreationException}
     * when it is checked, or the container's failure itself when nothing else threw.
     *
     * @param failure The failure to create the bean
     * @return The exception to throw
     */
    static RuntimeException translated(io.micronaut.context.exceptions.BeanCreationException failure) {
        Throwable cause = firstForeignCause(failure);
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause != null) {
            return new jakarta.enterprise.inject.CreationException(cause.getMessage(), cause);
        }
        return failure;
    }

    /**
     * The first cause that is not the container's own wrapping, which is what the bean's code threw: the
     * causes it carries in turn are its own business. The wrapping is told by its type - an exception of the
     * bean context, or the one an executable method wraps a checked exception in - and not by its package, which
     * an application's exception may share.
     */
    private static @org.jspecify.annotations.Nullable Throwable firstForeignCause(Throwable thrown) {
        // guarded against cause cycles of any length, which the platform permits
        java.util.Set<Throwable> walked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        walked.add(thrown);
        for (Throwable cause = thrown.getCause(); cause != null && walked.add(cause);
             cause = cause.getCause()) {
            if (!(cause instanceof io.micronaut.context.exceptions.BeanContextException)
                && !(cause instanceof io.micronaut.core.reflect.exception.InvocationException)) {
                return cause;
            }
        }
        return null;
    }

    /**
     * The client proxy of a bean in a normal scope, which is what a contextual reference to it is — or
     * {@code null} for a bean whose references are the instances themselves.
     *
     * @return The proxy, or {@code null}
     */
    public @org.jspecify.annotations.Nullable Object proxyReference() {
        if (!isNormalScoped()) {
            return null;
        }
        return beanContext.getBean(definition);
    }

    final boolean isNormalScoped() {
        return CdiResolution.isNormalScoped(definition.getAnnotationMetadata());
    }

    final boolean isDependent() {
        return !definition.isSingleton() && getScope() == jakarta.enterprise.context.Dependent.class;
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        try {
            if (creationalContext instanceof CdiCreationalContext<T> tracking && tracking.hasTracked()) {
                // what was created through this context is destroyed by releasing it, dependents included
                tracking.release();
                return;
            }
            // nothing was created through the context — the instance lives in its own scope, and what was
            // handed over may be the client proxy standing in front of it
            Object held = instance instanceof io.micronaut.aop.InterceptedProxy<?> proxy
                ? proxy.interceptedTarget() : instance;
            if (beanContext.findBeanRegistration(held).isPresent()) {
                beanContext.destroyBean(held);
            } else {
                // held by no scope - created by create() in a creational context of another kind - so it is
                // destroyed as an instance of this bean: its class may be the class of other beans as well
                destroyAs(targetDefinition(), held);
            }
        } catch (RuntimeException e) {
            // section 6.1.1: destroy catches what destruction throws, so that one failing pre-destroy does not
            // stop the rest of a context from being destroyed
        } finally {
            // whatever destruction did, the creational context is released: dependents go, and whoever handed
            // the context in sees the release
            creationalContext.release();
        }
    }

    @SuppressWarnings("unchecked")
    private <B> void destroyAs(BeanDefinition<B> of, Object instance) {
        beanContext.destroyBean(io.micronaut.context.BeanRegistration.of(beanContext,
            io.micronaut.inject.BeanIdentifier.of(of.getName()), of, (B) instance));
    }

    /**
     * What tells this bean from another: its definition, which Micronaut compares as the bean - a compiled
     * definition is equal to every other of its class, and one registered at runtime, as a synthetic bean is, only
     * to itself - or, for a proxy, the class of the definition it stands in front of: the proxy and its target are
     * the same bean.
     */
    private Object identity() {
        if (definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy) {
            return proxy.getTargetDefinitionType();
        }
        return definition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CdiBean<?> other)) {
            return false;
        }
        Object identity = identity();
        Object otherIdentity = other.identity();
        // the class of a proxy's target is the target's compiled definition, which is equal to its class's others
        if (identity instanceof Class<?> type) {
            return otherIdentity instanceof Class<?> ? type == otherIdentity : otherIdentity.getClass() == type;
        }
        if (otherIdentity instanceof Class<?> type) {
            return identity.getClass() == type;
        }
        return identity.equals(otherIdentity);
    }

    @Override
    public int hashCode() {
        // a compiled definition hashes as its class, which is what a proxy's target class hashes as
        return identity().hashCode();
    }

    @Override
    public String toString() {
        return "Bean[" + beanClass().getName() + "]";
    }
}
