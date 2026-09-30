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

import io.micronaut.core.type.Argument;
import io.micronaut.cdi.annotation.CdiScope;
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

    /**
     * The parameters Micronaut adds to the constructor of the subclass it generates for an intercepted bean, after
     * the ones the author wrote: the resolution context, the bean context, the qualifier, the interceptors and
     * the interceptor registry.
     */
    private static final Set<String> GENERATED_CONSTRUCTOR_PARAMETERS = Set.of(
        "$beanResolutionContext", "$beanContext", "$qualifier", "$interceptors", "$interceptorRegistry");

    private final BeanContext beanContext;
    private final BeanDefinition<T> definition;
    private volatile java.util.@Nullable List<CdiQualifier> qualifiers;

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
            .classValue("io.micronaut.cdi.annotation.CdiProducer", "declaringType").orElse(null);
        return declaring != null ? declaring : beanClass();
    }

    /**
     * Whether the argument is something the container hands a generated constructor rather than an injection
     * point the author wrote. It is told by what it is - one of the parameters Micronaut generates - and not by
     * the package of its type: an application may well inject a type of its own from a package of that name.
     */
    private static boolean isContainerMachinery(io.micronaut.core.type.Argument<?> argument) {
        return GENERATED_CONSTRUCTOR_PARAMETERS.contains(argument.getName());
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
        return new LinkedHashSet<>(io.micronaut.cdi.runtime.type.SpecificationTypes.typesOf(types()));
    }

    /**
     * The bean types of the bean as resolution compares them.
     *
     * @return The bean types
     */
    public java.util.List<Argument<?>> types() {
        return typesOf(definition, beanClass());
    }

    /**
     * The bean types of the bean the given definition describes, without an instance of this class around.
     *
     * @param definition The definition
     * @param beanClass  The class of the bean, with a proxy's target already resolved
     * @return The bean types
     */
    /**
     * The type closure the processor recorded for the bean, where it recorded one and every class of it can
     * be referred to from the definition.
     */
    private static java.util.@Nullable List<Argument<?>> recordedClosureOf(BeanDefinition<?> definition) {
        java.util.List<io.micronaut.core.annotation.AnnotationValue<Annotation>> records = definition
            .getAnnotationMetadata().findAnnotation("io.micronaut.cdi.annotation.CdiBeanTypes")
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

    static java.util.List<Argument<?>> typesOf(BeanDefinition<?> definition, Class<?> beanClass) {
        java.util.List<Argument<?>> types = new java.util.ArrayList<>();
        // the types a bean narrowed itself to are the ones it named with Typed, which is asked for rather than
        // Micronaut's own set of exposed types: those are what Micronaut resolves the bean by, and it exposes an
        // array by its component type as well, which is not a bean type of the array
        Class<?>[] narrowed = definition.getAnnotationMetadata()
            .classValues("jakarta.enterprise.inject.Typed");
        java.util.List<Argument<?>> closure = new java.util.ArrayList<>();
        java.util.List<Argument<?>> recorded = recordedClosureOf(definition);
        if (recorded != null) {
            // what the processor recorded of the bean as it compiled it
            closure.addAll(recorded);
        } else if (definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")) {
            // a produced bean is a bean of the type the producer declared — with the arguments it was written
            // with, its variables kept (section 3.3.2), or raw if it was written raw — not of the produced
            // class's own declaration
            closure.addAll(CdiTypes.beanTypeClosureOf(definition.getDeclaredBeanType()));
        } else {
            // the bean types of a bean are every class and interface its own type is assignable to, with the
            // parameters a generic type was written with: a generic class is a bean of its parameterized form
            // rather than of its erasure
            if (CdiTypes.knowsClosureOf(beanClass)) {
                closure.addAll(CdiTypes.beanTypeClosureOf(beanClass));
            } else {
                // a bean compiled without this processor, of which nothing was recorded: it is resolvable by
                // its class and by the raw types Micronaut exposes it as
                closure.add(Argument.of(beanClass));
                for (Class<?> exposed : definition.getExposedTypes()) {
                    closure.add(Argument.of(exposed));
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
        return CdiQualifier.instances(qualifiers());
    }

    /**
     * The qualifiers of the bean as resolution compares them: the ones it was compiled with, and {@code Any},
     * which every bean has.
     *
     * @return The qualifiers
     */
    final java.util.List<CdiQualifier> qualifiers() {
        java.util.List<CdiQualifier> resolved = qualifiers;
        if (resolved == null) {
            java.util.List<CdiQualifier> all = new java.util.ArrayList<>(3);
            all.add(CdiQualifier.ANY);
            for (CdiQualifier declared : CdiQualifier.declared(definition.getAnnotationMetadata())) {
                if (!declared.isAny()) {
                    all.add(declared);
                }
            }
            resolved = java.util.List.copyOf(all);
            QualifierOverlay overlay = beanContext.findBean(QualifierOverlay.class).orElse(null);
            if (overlay != null) {
                // what a portable extension added to the bean class as the container started
                resolved = overlay.apply(definition, resolved);
            }
            qualifiers = resolved;
        }
        return resolved;
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
        // the stereotype-given name first: where one was recorded, the jakarta annotation beside it is only
        // the default Micronaut materialized, spelled by Micronaut's rules rather than the specification's
        return definition.getAnnotationMetadata().stringValue("io.micronaut.cdi.annotation.CdiName")
            .or(() -> definition.getAnnotationMetadata().stringValue("jakarta.inject.Named"))
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
        io.micronaut.core.annotation.AnnotationValue<java.lang.annotation.Annotation> producer =
            described.getAnnotationMetadata().getAnnotation("io.micronaut.cdi.annotation.CdiProducer");
        if (producer == null) {
            for (io.micronaut.core.type.Argument<?> argument : constructor.getArguments()) {
                if (isContainerMachinery(argument)) {
                    // what the container itself passes a generated constructor is not an injection point
                    continue;
                }
                points.add(new CdiInjectionPoint(this, argument, declaring, "<init>", false));
            }
        } else if (!producer.booleanValue("field").orElse(false)) {
            // section 2.2.2.2: all producer method parameters are injection points. The producer method is what
            // Micronaut constructs the bean with, and its parameters are the ones of the method the producer
            // names; a producer field has none
            String member = producer.stringValue("member").orElse(null);
            for (io.micronaut.core.type.Argument<?> argument : constructor.getArguments()) {
                points.add(new CdiInjectionPoint(this, argument, getBeanClass(), member, false));
            }
        }
        for (io.micronaut.inject.FieldInjectionPoint<T, ?> field : described.getInjectedFields()) {
            points.add(new CdiInjectionPoint(this, field.asArgument(), declaring, field.getName(), true));
        }
        for (io.micronaut.inject.MethodInjectionPoint<T, ?> method : described.getInjectedMethods()) {
            if (method.isPostConstructMethod() || method.isPreDestroyMethod()) {
                continue;
            }
            for (io.micronaut.core.type.Argument<?> argument : method.getArguments()) {
                points.add(new CdiInjectionPoint(this, argument, declaring, method.getName(), false));
            }
        }
        return points;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        try {
            if (isDependent() && creationalContext instanceof CdiCreationalContext<T> tracking) {
                // a dependent instance belongs to whoever asked for it, and what was created along with it
                // belongs to it: the registration carries both, and releasing the creational context closes it
                io.micronaut.context.BeanRegistration<T> registration =
                    beanContext.getBeanRegistration(definition);
                tracking.track(registration);
                return registration.bean();
            }
            // section 2.5.1: create() creates a new contextual instance. That a scope has one instance is the
            // business of its context, which creates it once and hands the same one out - scopedInstance()
            io.micronaut.context.scope.CreatedBean<T> created = createOutsideOfScope();
            if (created == null) {
                return scopedInstance();
            }
            if (creationalContext instanceof CdiCreationalContext<T> tracking) {
                tracking.track(created);
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
     * Creates an instance of a bean that has a scope, without the scope holding it.
     *
     * <p>A bean of a normal scope is created by its scope, asked for a new instance, with everything a creation
     * is and the dependents it was created with. A singleton is created by Micronaut from its definition, again
     * with everything a creation is; what was created along with it is not reported, so that instance is destroyed
     * without its dependent objects.</p>
     *
     * @return What was created, or {@code null} for a bean of a scope that offers no such creation
     */
    private io.micronaut.context.scope.@org.jspecify.annotations.Nullable CreatedBean<T> createOutsideOfScope() {
        if (isNormalScoped()) {
            io.micronaut.context.scope.CustomScope<?> scope = declaredScope();
            if (scope == null) {
                return null;
            }
            return io.micronaut.cdi.context.FreshInstance.create(scope,
                () -> beanContext.getProxyTargetBean(targetArgument(), definition.getDeclaredQualifier()));
        }
        if (definition.isSingleton() && !isRuntimeDefinition()) {
            T instance = beanContext.createBean(definition.getBeanType(), onlyThisDefinition());
            return io.micronaut.context.BeanRegistration.of(beanContext,
                io.micronaut.inject.BeanIdentifier.of(definition.getName()), definition, instance);
        }
        return null;
    }

    /**
     * The scope Micronaut holds the instances of this bean in: the one whose annotation the bean declares.
     */
    private io.micronaut.context.scope.@org.jspecify.annotations.Nullable CustomScope<?> declaredScope() {
        AnnotationMetadata metadata = targetDefinition().getAnnotationMetadata();
        for (io.micronaut.context.scope.CustomScope<?> scope
            : beanContext.getBeansOfType(io.micronaut.context.scope.CustomScope.class)) {
            if (metadata.hasStereotype(scope.annotationType())) {
                return scope;
            }
        }
        return null;
    }

    private io.micronaut.context.Qualifier<T> onlyThisDefinition() {
        return new io.micronaut.context.Qualifier<T>() {
            @Override
            public <B extends io.micronaut.inject.BeanType<T>> java.util.stream.Stream<B> reduce(
                Class<T> beanType, java.util.stream.Stream<B> candidates) {
                return candidates.filter(candidate -> candidate.equals(definition));
            }
        };
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
     * causes it carries in turn are its own business.
     */
    private static @org.jspecify.annotations.Nullable Throwable firstForeignCause(Throwable thrown) {
        // guarded against cause cycles of any length, which the platform permits
        java.util.Set<Throwable> walked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        walked.add(thrown);
        for (Throwable cause = thrown.getCause(); cause != null && walked.add(cause);
             cause = cause.getCause()) {
            if (!cause.getClass().getName().startsWith("io.micronaut.")) {
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
        return definition.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false);
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
     * The class of the one definition the compiler wrote for this bean, which a proxy definition stands in
     * front of: the proxy and its target are the same bean.
     */
    private String canonicalDefinitionName() {
        if (definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy) {
            return proxy.getTargetDefinitionType().getName();
        }
        return definition.getClass().getName();
    }

    /**
     * A definition registered at runtime, as a synthetic bean is: every one shares a class, so each is a bean of
     * its own, compared by identity.
     */
    private boolean isRuntimeDefinition() {
        return definition instanceof io.micronaut.context.RuntimeBeanDefinition<?>;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CdiBean<?> other)) {
            return false;
        }
        if (isRuntimeDefinition() || other.isRuntimeDefinition()) {
            return definition == other.definition;
        }
        return canonicalDefinitionName().equals(other.canonicalDefinitionName());
    }

    @Override
    public int hashCode() {
        if (isRuntimeDefinition()) {
            return System.identityHashCode(definition);
        }
        return canonicalDefinitionName().hashCode();
    }

    @Override
    public String toString() {
        return "Bean[" + beanClass().getName() + "]";
    }
}
