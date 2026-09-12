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
package io.quarkus.arc;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.cdi.runtime.CdiBean;
import io.micronaut.cdi.runtime.CdiInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The container a test of ArC asks for beans: the application context the test's extension started.
 *
 * <p>Everything a test does through this goes to the runtime of this implementation. A lookup is a
 * {@code CdiInstance}, which is the specification's {@code Instance}; the bean manager is this implementation's
 * own, wrapped only so that a bean it hands out answers the three questions ArC's interface adds; and a request
 * is the request scope's.</p>
 */
public final class ArcContainer {

    private final ApplicationContext context;

    /**
     * @param context The context the deployment was started as
     */
    public ArcContainer(ApplicationContext context) {
        this.context = context;
    }

    /**
     * @return The context behind this container
     */
    public ApplicationContext context() {
        return context;
    }

    /**
     * Looks a bean up by type.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers
     * @param <T>        The required type
     * @return A handle on the bean, which is unavailable where nothing was resolved
     */
    public <T> InstanceHandle<T> instance(Class<T> type, Annotation... qualifiers) {
        return handleOf(Argument.of(type), qualifiers);
    }

    /**
     * Looks a bean up by a type that carries its own arguments.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers
     * @param <T>        The required type
     * @return A handle on the bean
     */
    public <T> InstanceHandle<T> instance(TypeLiteral<T> type, Annotation... qualifiers) {
        return handleOf(argumentOf(type.getType()), qualifiers);
    }

    /**
     * Looks a bean up by a reflective type.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers
     * @return A handle on the bean
     */
    public InstanceHandle<Object> instance(Type type, Annotation... qualifiers) {
        return handleOf(argumentOf(type), qualifiers);
    }

    /**
     * Hands out a handle on a bean the test already has.
     *
     * @param bean The bean
     * @param <T>  The bean type
     * @return A handle on it
     */
    public <T> InstanceHandle<T> instance(InjectableBean<T> bean) {
        return CdiBackedHandle.of(context, ((CdiBackedBean<T>) bean).delegate());
    }

    /**
     * Looks a bean up by the name it was given with {@code @Named}.
     *
     * @param name The bean's name
     * @return A handle on the bean
     */
    public InstanceHandle<Object> instance(String name) {
        BeanManager beanManager = context.getBean(BeanManager.class);
        Bean<?> resolved = beanManager.resolve(beanManager.getBeans(name));
        if (resolved == null) {
            return CdiBackedHandle.unavailable();
        }
        return CdiBackedHandle.of(context, (CdiBean<Object>) resolved);
    }

    /**
     * Begins a programmatic lookup, which is what the specification calls {@code Instance}.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers
     * @param <T>        The required type
     * @return The lookup
     */
    public <T> InjectableInstance<T> select(Class<T> type, Annotation... qualifiers) {
        return CdiBackedInstance.of(context, Argument.of(type), qualifiers);
    }

    /**
     * Begins a programmatic lookup for a type that carries its own arguments.
     *
     * @param type       The required type
     * @param qualifiers The required qualifiers
     * @param <T>        The required type
     * @return The lookup
     */
    public <T> InjectableInstance<T> select(TypeLiteral<T> type, Annotation... qualifiers) {
        return CdiBackedInstance.of(context, this.<T>argumentOf(type.getType()), qualifiers);
    }

    /**
     * @return The bean manager of the deployment, whose beans answer ArC's questions as well as the
     *     specification's
     */
    public BeanManager beanManager() {
        BeanManager delegate = context.getBean(BeanManager.class);
        return (BeanManager) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{BeanManager.class}, (proxy, method, args) -> {
                // the one thing ArC's tests need of the bean manager that the specification's interface does not
                // give them: a bean they can ask for its priority, its identifier and its declaring bean. The
                // beans it hands out are wrapped on the way out, and unwrapped again on the way back in
                Object[] unwrapped = args == null ? null : unwrap(args);
                Object result = method.invoke(delegate, unwrapped);
                if (result instanceof Set<?> set && "getBeans".equals(method.getName())) {
                    Set<Object> wrapped = new LinkedHashSet<>(set.size());
                    for (Object each : set) {
                        wrapped.add(wrap(each));
                    }
                    return wrapped;
                }
                return wrap(result);
            });
    }

    /**
     * @return A request the test begins and ends itself
     */
    public ManagedContext requestContext() {
        RequestScope scope = context.getBean(RequestScope.class);
        return new ManagedContext() {
            @Override
            public void activate() {
                scope.activate();
            }

            @Override
            public void deactivate() {
                scope.deactivate();
            }

            @Override
            public void terminate() {
                scope.deactivate();
            }

            @Override
            public boolean isActive() {
                return scope.isActive();
            }
        };
    }

    /**
     * Finds a bean by the identifier another bean of the same container reported.
     *
     * @param identifier The identifier
     * @param <T>        The bean type
     * @return The bean, or null where the container has none with that identifier
     */
    public <T> @Nullable InjectableBean<T> bean(String identifier) {
        for (Bean<?> bean : beanManager().getBeans(Object.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)) {
            if (bean instanceof InjectableBean<?> injectable && injectable.getIdentifier().equals(identifier)) {
                return (InjectableBean<T>) injectable;
            }
        }
        return null;
    }

    /**
     * Stops the deployment.
     */
    public void shutdown() {
        context.close();
    }

    private <T> InstanceHandle<T> handleOf(Argument<T> type, Annotation... qualifiers) {
        CdiInstance<T> lookup = new CdiInstance<>(context, type, qualifiers);
        if (lookup.isUnsatisfied()) {
            return CdiBackedHandle.unavailable();
        }
        return new CdiBackedHandle<>(context, lookup.getHandle());
    }

    @SuppressWarnings("unchecked")
    private <T> Argument<T> argumentOf(Type type) {
        return (Argument<T>) Argument.of(type);
    }

    private static Object[] unwrap(Object[] args) {
        Object[] unwrapped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            unwrapped[i] = args[i] instanceof CdiBackedBean<?> wrapper ? wrapper.delegate() : args[i];
        }
        return unwrapped;
    }

    private static @Nullable Object wrap(@Nullable Object result) {
        return result instanceof CdiBean<?> bean ? new CdiBackedBean<>(bean) : result;
    }
}
