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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.interceptor.annotation.InterceptionKind;
import io.micronaut.interceptor.runtime.InterceptorMethods;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * An interceptor class of the container, described the way the specification's own metamodel describes one.
 *
 * <p>The interception itself is the business of the Jakarta Interceptors implementation, which resolved and
 * compiled it into the beans it intercepts; what this answers is the questions the bean manager can be asked
 * about an interceptor — what it binds to, what kinds of interception it performs, and, as any bean, how an
 * instance of it is created and destroyed. Notifying it directly through {@link #intercept} invokes the
 * interceptor methods the annotation processor recorded on the definition.</p>
 *
 * @param <T> The interceptor class
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiInterceptor<T> implements Interceptor<T> {

    private final BeanContext beanContext;
    private final BeanDefinition<T> definition;
    private final CdiBean<T> bean;

    CdiInterceptor(BeanContext beanContext, BeanDefinition<T> definition) {
        this.beanContext = beanContext;
        this.definition = definition;
        this.bean = new CdiBean<>(beanContext, definition);
    }

    /**
     * Whether an interceptor takes part in interception at all, which section 5.1 ties to it declaring a
     * priority.
     *
     * @return Whether the interceptor is enabled
     */
    boolean isEnabled() {
        // answered by what the interceptors implementation asks as it resolves a chain, so that what is reported
        // here is what runs
        return beanContext.getBean(CdiInterceptorEnablement.class).isEnabled(definition);
    }

    /**
     * The priority the interceptor declared, which orders a resolved chain lowest first.
     *
     * @return The priority
     */
    int priority() {
        // read by the interceptors implementation, the way it orders a chain, so that the order this reports is the
        // order that runs
        return InterceptorMethods.priorityOf(definition);
    }

    @Override
    public Set<Annotation> getInterceptorBindings() {
        return CdiQualifier.instances(bindings());
    }

    /**
     * The interceptor bindings of the interceptor as resolution compares them. An annotation the interceptor
     * carries is a binding where its own type is one, which is what was recorded of the type while the
     * application compiled, and is asked of the annotation class only for a type nothing was recorded of.
     *
     * @return The bindings
     */
    java.util.List<CdiQualifier> bindings() {
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        java.util.List<CdiQualifier> bindings = new java.util.ArrayList<>(2);
        for (String name : metadata.getAnnotationNamesByStereotype("jakarta.interceptor.InterceptorBinding")) {
            AnnotationValue<?> value = metadata.getAnnotation(name);
            if (isBindingType(metadata, name)) {
                // a binding a binding carries is recorded by name, with no member written
                bindings.add(CdiQualifier.ofCompiled(metadata, value != null ? value : new AnnotationValue<>(name)));
            }
        }
        return bindings;
    }

    private static boolean isBindingType(AnnotationMetadata metadata, String name) {
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        if (recorded != null) {
            return recorded.binding();
        }
        Class<? extends Annotation> type = metadata.getAnnotationType(name).orElse(null);
        return type != null && CdiReflection.current("Whether " + name + ", an annotation the application was "
            + "not compiled with as an interceptor binding, is one")
            .isAnnotated(type, jakarta.interceptor.InterceptorBinding.class);
    }

    @Override
    public boolean intercepts(InterceptionType type) {
        return methodsOf(type) != null;
    }

    @Override
    public @Nullable Object intercept(InterceptionType type, T instance, InvocationContext ctx) throws Exception {
        InterceptorMethods methods = methodsOf(type);
        if (methods == null) {
            throw new IllegalArgumentException("The interceptor " + getBeanClass().getName()
                + " does not perform " + type + " interception");
        }
        // the methods are invoked most general superclass first; each is given a context whose proceed is the next,
        // and the last proceeds into the invocation itself. What the invocation throws, checked or not, is the
        // interceptor's to see and the caller's to catch: section 2.5 of Jakarta Interceptors has an exception
        // travel through the chain as it was thrown
        return methods.invoke(instance, ctx);
    }

    @Override
    public Class<?> getBeanClass() {
        return bean.getBeanClass();
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return bean.getInjectionPoints();
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        return bean.create(creationalContext);
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        bean.destroy(instance, creationalContext);
    }

    @Override
    public Set<Type> getTypes() {
        return bean.getTypes();
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return bean.getQualifiers();
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return bean.getScope();
    }

    @Override
    public @Nullable String getName() {
        return bean.getName();
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        return bean.getStereotypes();
    }

    @Override
    public boolean isAlternative() {
        return bean.isAlternative();
    }

    /**
     * The interceptor methods that interpose on a kind of interception, read by the interceptors implementation the
     * way it reads them for a chain, so that what is reported and invoked here is what a chain invokes.
     *
     * @return The methods, or {@code null} where the interceptor does not interpose on the kind
     */
    private @Nullable InterceptorMethods methodsOf(InterceptionType type) {
        InterceptionKind kind = switch (type) {
            case AROUND_INVOKE -> InterceptionKind.AROUND_INVOKE;
            case AROUND_TIMEOUT -> InterceptionKind.AROUND_TIMEOUT;
            case AROUND_CONSTRUCT -> InterceptionKind.AROUND_CONSTRUCT;
            case POST_CONSTRUCT -> InterceptionKind.POST_CONSTRUCT;
            case PRE_DESTROY -> InterceptionKind.PRE_DESTROY;
            // passivation belongs to CDI Full, and no interceptor here performs it
            case PRE_PASSIVATE, POST_ACTIVATE -> null;
        };
        if (kind == null) {
            return null;
        }
        InterceptorMethods methods = InterceptorMethods.of(definition, kind);
        return methods.isEmpty() ? null : methods;
    }

    @Override
    public String toString() {
        return "Interceptor[" + getBeanClass().getName() + "]";
    }
}
