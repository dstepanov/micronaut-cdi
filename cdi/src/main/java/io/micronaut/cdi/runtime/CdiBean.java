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
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiBean<T> implements Bean<T> {

    private final BeanContext beanContext;
    private final BeanDefinition<T> definition;

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
     * point the author wrote: its type, or a type inside it, belongs to the container.
     */
    private static boolean isContainerMachinery(io.micronaut.core.type.Argument<?> argument) {
        if (argument.getType().getName().startsWith("io.micronaut.")) {
            return true;
        }
        for (io.micronaut.core.type.Argument<?> parameter : argument.getTypeParameters()) {
            if (isContainerMachinery(parameter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The definition of the class itself, which for a bean in a normal scope is the proxy's target.
     */
    @SuppressWarnings("unchecked")
    private BeanDefinition<T> targetDefinition() {
        if (definition instanceof ProxyBeanDefinition<T> proxy) {
            for (BeanDefinition<?> candidate : beanContext.getBeanDefinitions(proxy.getTargetType())) {
                if (candidate.getClass().getName().equals(proxy.getTargetDefinitionType().getName())) {
                    return (BeanDefinition<T>) candidate;
                }
            }
        }
        return definition;
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
        return typesOf(definition, beanClass());
    }

    /**
     * The bean types of the bean the given definition describes, without an instance of this class around.
     *
     * @param definition The definition
     * @param beanClass  The class of the bean, with a proxy's target already resolved
     * @return The bean types
     */
    static Set<Type> typesOf(BeanDefinition<?> definition, Class<?> beanClass) {
        Set<Type> types = new LinkedHashSet<>();
        // the types a bean narrowed itself to are the ones it named with Typed, which is asked for rather than
        // Micronaut's own set of exposed types: those are what Micronaut resolves the bean by, and it exposes an
        // array by its component type as well, which is not a bean type of the array
        Class<?>[] narrowed = definition.getAnnotationMetadata()
            .classValues("jakarta.enterprise.inject.Typed");
        Set<Type> closure = new LinkedHashSet<>();
        if (definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")) {
            // a produced bean is a bean of the type the producer declared — with the arguments it was written
            // with, or raw if it was written raw — not of the produced class's own declaration
            boolean raw = definition.getAnnotationMetadata()
                .booleanValue("io.micronaut.cdi.annotation.CdiProducer", "raw").orElse(false);
            Type produced;
            if (raw) {
                produced = definition.getBeanType();
            } else {
                produced = CdiTypes.typeOf(definition.asArgument());
                // a producer of a type containing the class's variables keeps them (section 3.3.2), and the
                // compiled argument erased them: what was recorded about them at compile time puts them back
                produced = CdiTypes.applyRecordedVariables(produced,
                    definition.getAnnotationMetadata()
                        .stringValues("io.micronaut.cdi.annotation.CdiGenericVariables"),
                    definition.getBeanType().getClassLoader());
            }
            collectTypes(produced, closure);
        } else {
            // the bean types of a bean are every class and interface its own type is assignable to, with the
            // parameters a generic type was written with: a generic class is a bean of its parameterized form
            // rather than of its erasure
            collectTypes(CdiParameterizedType.of(beanClass), closure);
        }
        if (definition.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Typed")) {
            // the types the bean named with Typed keep the parameters the closure gives them: an Emu typed
            // FlightlessBird is a bean of FlightlessBird<Australian>, which is what it extends
            Set<Class<?>> kept = new LinkedHashSet<>(java.util.Arrays.asList(narrowed));
            for (Type candidate : closure) {
                Class<?> raw = candidate instanceof Class<?> aClass ? aClass
                    : candidate instanceof java.lang.reflect.ParameterizedType parameterized
                        && parameterized.getRawType() instanceof Class<?> rawType ? rawType : null;
                if (raw != null && kept.contains(raw)) {
                    types.add(candidate);
                }
            }
        } else {
            types.addAll(closure);
        }
        // a parameterized type containing a wildcard is not a legal bean type (section 2.2.1): a supertype
        // written that way is simply not among the types the bean can be resolved by
        types.removeIf(type -> !CdiAssignability.isLegalBeanType(type));
        // every bean has Object among its types, whatever it narrowed them to
        types.add(Object.class);
        return types;
    }

    private static void collectTypes(@Nullable Type type, Set<Type> types) {
        if (type == null || type == Object.class) {
            return;
        }
        Class<?> raw;
        if (type instanceof Class<?> aClass) {
            raw = aClass;
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawType) {
            raw = rawType;
        } else {
            // a type variable or a wildcard names no type of its own
            return;
        }
        types.add(type);
        if (raw.isArray() || raw.isPrimitive()) {
            // the bean types of an array are the array and Object: the interfaces every array implements are
            // not among them, and a primitive has none to collect
            return;
        }
        // what the type says about its parameters carries into its supertypes: ArrayList<String> is a bean of
        // List<String>, not of List<E>
        java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution = new java.util.HashMap<>();
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                substitution.put(variables[i], arguments[i]);
            }
        }
        for (Type anInterface : raw.getGenericInterfaces()) {
            collectTypes(substitute(anInterface, substitution), types);
        }
        collectTypes(substitute(raw.getGenericSuperclass(), substitution), types);
    }

    private static @Nullable Type substitute(@Nullable Type type,
                                             java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution) {
        if (substitution.isEmpty() || type == null) {
            return type;
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            return substitution.getOrDefault(variable, variable);
        }
        if (type instanceof java.lang.reflect.ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawType) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                substituted[i] = substitute(arguments[i], substitution);
                changed |= substituted[i] != arguments[i];
            }
            return changed ? CdiParameterizedType.of(rawType, substituted) : type;
        }
        return type;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return CdiQualifiers.of(definition.getAnnotationMetadata());
    }

    @Override
    public Class<? extends Annotation> getScope() {
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        Class<? extends Annotation> written = metadata.stringValue(CdiScope.class)
            .map(CdiBean::scopeNamed)
            .orElse(null);
        if (written != null) {
            return written;
        }
        // a bean that was not written with a scope of the specification is one of Micronaut's own, and the two
        // scopes it can be in are the ones the specification also has
        return definition.isSingleton() ? Singleton.class : Dependent.class;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Annotation> scopeNamed(String name) {
        try {
            return (Class<? extends Annotation>) Class.forName(name, false, CdiBean.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
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
            Class<? extends Annotation> stereotype = scopeNamed(name);
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
        if (constructor.getArguments().length > 0 && !described.getAnnotationMetadata()
            .hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")) {
            for (io.micronaut.core.type.Argument<?> argument : constructor.getArguments()) {
                if (isContainerMachinery(argument)) {
                    // what the container itself passes a generated constructor is not an injection point
                    continue;
                }
                points.add(new CdiInjectionPoint(this, argument, declaring, "<init>", false));
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
            if (isNormalScoped() && beanContext instanceof io.micronaut.context.DefaultBeanContext defaultContext) {
                // creating a bean in a normal scope is creating the contextual instance the scope holds, not
                // the client proxy in front of it: a producer that misbehaves — returning null, say — is
                // heard from here, as the specification's create contract expects
                @SuppressWarnings("unchecked")
                io.micronaut.core.type.Argument<T> target =
                    definition instanceof io.micronaut.inject.ProxyBeanDefinition<T> proxy
                        ? (io.micronaut.core.type.Argument<T>) io.micronaut.core.type.Argument.of(
                            proxy.getTargetType(), definition.asArgument().getTypeParameters())
                        : definition.asArgument();
                return defaultContext.getProxyTargetBean(target, definition.getDeclaredQualifier());
            }
            return beanContext.getBean(definition);
        } catch (io.micronaut.context.exceptions.BeanCreationException e) {
            // section 6.1.1: what the bean itself threw comes out as it was thrown if it is unchecked, and
            // wrapped in a CreationException if it is checked
            Throwable cause = deepestForeignCause(e);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause != null) {
                throw new jakarta.enterprise.inject.CreationException(cause.getMessage(), cause);
            }
            throw e;
        }
    }

    /**
     * The deepest cause that is not the container's own wrapping, which is what the bean's code threw.
     */
    static @io.micronaut.core.annotation.Nullable Throwable deepestForeignCause(Throwable thrown) {
        Throwable foreign = null;
        // guarded against cause cycles of any length, which the platform permits
        java.util.Set<Throwable> walked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        walked.add(thrown);
        for (Throwable cause = thrown.getCause(); cause != null && walked.add(cause);
             cause = cause.getCause()) {
            if (!cause.getClass().getName().startsWith("io.micronaut.")) {
                foreign = cause;
            }
        }
        return foreign;
    }

    /**
     * The client proxy of a bean in a normal scope, which is what a contextual reference to it is — or
     * {@code null} for a bean whose references are the instances themselves.
     *
     * @return The proxy, or {@code null}
     */
    public @io.micronaut.core.annotation.Nullable Object proxyReference() {
        if (!isNormalScoped()) {
            return null;
        }
        return beanContext.getBean(definition);
    }

    private boolean isNormalScoped() {
        return definition.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false);
    }

    private boolean isDependent() {
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
            beanContext.destroyBean(held);
        } catch (RuntimeException e) {
            // section 6.1.1: destroy catches what destruction throws, so that one failing pre-destroy does not
            // stop the rest of a context from being destroyed
        } finally {
            // whatever destruction did, the creational context is released: dependents go, and whoever handed
            // the context in sees the release
            creationalContext.release();
        }
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

    @Override
    public boolean equals(Object o) {
        return o instanceof CdiBean<?> other && canonicalDefinitionName().equals(other.canonicalDefinitionName());
    }

    @Override
    public int hashCode() {
        return canonicalDefinitionName().hashCode();
    }

    @Override
    public String toString() {
        return "Bean[" + beanClass().getName() + "]";
    }
}
