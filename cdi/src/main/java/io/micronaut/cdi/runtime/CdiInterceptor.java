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
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.interceptor.annotation.JakartaInterceptorMethods;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final String ORDER = "io.micronaut.core.annotation.Order";
    private static final String PRIORITY = "jakarta.annotation.Priority";

    private final BeanDefinition<T> definition;
    private final CdiBean<T> bean;

    CdiInterceptor(BeanContext beanContext, BeanDefinition<T> definition) {
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
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        return metadata.hasAnnotation(ORDER) || metadata.hasAnnotation(PRIORITY);
    }

    /**
     * The priority the interceptor declared, which orders a resolved chain lowest first.
     *
     * @return The priority
     */
    int priority() {
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        // jakarta.annotation.Priority is remapped to Order with the value as-is, and the original may be
        // dropped along the way, so both forms are read
        java.util.OptionalInt order = metadata.intValue(ORDER, "value");
        if (order.isPresent()) {
            return order.getAsInt();
        }
        return metadata.intValue(PRIORITY, "value").orElse(0);
    }

    @Override
    public Set<Annotation> getInterceptorBindings() {
        AnnotationMetadata metadata = definition.getAnnotationMetadata();
        Set<Annotation> bindings = new LinkedHashSet<>();
        for (String name : metadata.getAnnotationNamesByStereotype("jakarta.interceptor.InterceptorBinding")) {
            AnnotationValue<?> value = metadata.getAnnotation(name);
            Class<? extends Annotation> type = annotationClassOf(name);
            if (type != null && type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                bindings.add(CdiAnnotations.annotationOf(type, value));
            }
        }
        return bindings;
    }

    @Override
    public boolean intercepts(InterceptionType type) {
        return !methodNamesOf(type).isEmpty();
    }

    @Override
    @SuppressWarnings("NullAway")
    public Object intercept(InterceptionType type, T instance, InvocationContext ctx) {
        List<String> names = methodNamesOf(type);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("The interceptor " + getBeanClass().getName()
                + " does not perform " + type + " interception");
        }
        // the methods are recorded most general superclass first, which is the order they are invoked in; each
        // is given a context whose proceed is the next, and the last proceeds into the invocation itself
        return invokeFrom(0, names, instance, ctx);
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

    private List<String> methodNamesOf(InterceptionType type) {
        AnnotationValue<JakartaInterceptorMethods> methods =
            definition.getAnnotationMetadata().getAnnotation(JakartaInterceptorMethods.class);
        if (methods == null) {
            return List.of();
        }
        String member = switch (type) {
            case AROUND_INVOKE -> "aroundInvoke";
            case AROUND_TIMEOUT -> "aroundTimeout";
            case AROUND_CONSTRUCT -> "aroundConstruct";
            case POST_CONSTRUCT -> "postConstruct";
            case PRE_DESTROY -> "preDestroy";
            // passivation belongs to CDI Full, and no interceptor here performs it
            case PRE_PASSIVATE, POST_ACTIVATE -> null;
        };
        return member == null ? List.of() : List.of(methods.stringValues(member));
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object invokeFrom(int index, List<String> names, T instance, InvocationContext ctx) {
        if (index == names.size()) {
            try {
                return ctx.proceed();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("The interception could not proceed", e);
            }
        }
        ExecutableMethod<T, ?> method = methodNamed(names.get(index));
        InvocationContext next = new NextInContext(ctx, () -> invokeFrom(index + 1, names, instance, ctx));
        return ((ExecutableMethod<T, Object>) method).invoke(instance, next);
    }

    @SuppressWarnings("unchecked")
    private ExecutableMethod<T, ?> methodNamed(String name) {
        for (ExecutableMethod<? super T, ?> method : definition.getExecutableMethods()) {
            if (method.getName().equals(name) && method.getArguments().length == 1
                && InvocationContext.class.isAssignableFrom(method.getArguments()[0].getType())) {
                return (ExecutableMethod<T, ?>) method;
            }
        }
        throw new IllegalStateException("The interceptor method " + name + " of " + getBeanClass().getName()
            + " was recorded at compilation but is not among the executable methods");
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Annotation> annotationClassOf(String name) {
        try {
            return (Class<? extends Annotation>) Class.forName(name, false,
                Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "Interceptor[" + getBeanClass().getName() + "]";
    }

    /**
     * The context the next interceptor method of the same chain sees: everything of the invocation, with proceed
     * leading to that next method.
     *
     * @param invocation The context of the invocation itself
     * @param next       Where proceeding from here leads
     */
    private record NextInContext(InvocationContext invocation, ProceedsTo next) implements InvocationContext {

        @Override
        public java.util.Set<Annotation> getInterceptorBindings() {
            // the bindings are the caller's: the wrapper only moves the chain along
            return invocation.getInterceptorBindings();
        }

        @Override
        public Object getTarget() {
            return invocation.getTarget();
        }

        @Override
        public Object getTimer() {
            return invocation.getTimer();
        }

        @Override
        public java.lang.reflect.Method getMethod() {
            return invocation.getMethod();
        }

        @Override
        public java.lang.reflect.Constructor<?> getConstructor() {
            return invocation.getConstructor();
        }

        @Override
        public Object[] getParameters() {
            return invocation.getParameters();
        }

        @Override
        public void setParameters(Object[] params) {
            invocation.setParameters(params);
        }

        @Override
        public java.util.Map<String, Object> getContextData() {
            return invocation.getContextData();
        }

        @Override
        public @Nullable Object proceed() {
            return next.proceed();
        }
    }

    @FunctionalInterface
    private interface ProceedsTo {
        @Nullable
        Object proceed();
    }
}
