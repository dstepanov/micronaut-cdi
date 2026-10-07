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

import io.micronaut.cdi.internal.metadata.CdiDisposer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanDependencyGroup;
import io.micronaut.context.annotation.ResolveWith;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * Invokes the disposer method of a produced bean as the bean is destroyed.
 *
 * <p>The specification has a bean that was produced disposed of by the disposer method declared beside its
 * producer, rather than by a callback on the bean itself. Which method that is was resolved while the producer was
 * compiled and recorded on it with {@link CdiDisposer}; all that is left to do here is to invoke it, on an
 * instance of the class that declares it, at the moment Micronaut destroys the bean.</p>
 *
 * <p>The disposer takes the bean it disposes of as its {@code Disposes} parameter, and may take further
 * parameters, which are injection points and are resolved from the container as any other injection point is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class DisposerInvoker implements BeanPreDestroyEventListener<Object> {

    private final BeanContext beanContext;
    /**
     * The disposer of each producer whose product was destroyed, found once: every instance a dependent producer
     * makes is disposed of by the same method.
     */
    private final java.util.concurrent.ConcurrentHashMap<ProducerKey, Disposer> disposers =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile @Nullable CdiBeanContainer container;

    public DisposerInvoker(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Object onPreDestroy(BeanPreDestroyEvent<Object> event) {
        Object bean = event.getBean();
        BeanDefinition<Object> definition = event.getBeanDefinition();
        Disposer found = disposers.get(new ProducerKey(definition));
        if (found == null) {
            AnnotationValue<CdiDisposer> disposer = definition.getAnnotation(CdiDisposer.class);
            if (disposer == null) {
                // every bean of the context is destroyed through here, and few of them were produced
                return bean;
            }
            found = disposerOf(disposer);
            if (found == null) {
                return bean;
            }
            disposers.putIfAbsent(new ProducerKey(definition), found);
        }
        Disposer disposer = found;
        return event.withDependencies(dependencies -> {
            if (disposer.staticMethod()) {
                invokeStatic(disposer, bean, dependencies);
            } else {
                invoke(disposer, bean, dependencies);
            }
            return bean;
        });
    }

    private @Nullable Disposer disposerOf(AnnotationValue<CdiDisposer> disposer) {
        Optional<Class<?>> declaringType = disposer.classValue("declaringType");
        String methodName = disposer.stringValue("method").orElse(null);
        int disposedParameter = disposer.intValue("disposedParameter").orElse(-1);
        if (declaringType.isEmpty() || methodName == null || disposedParameter < 0) {
            return null;
        }
        boolean staticMethod = disposer.booleanValue("staticMethod").orElse(false);
        BeanDefinition<?> declaring = beanContext.getBeanDefinition(declaringType.get());
        ExecutableMethod<?, ?> method = findMethod(declaring, methodName, disposer.stringValues("parameterTypes"))
            .orElseThrow(() -> new IllegalStateException("The " + (staticMethod ? "static " : "")
                + "disposer method " + methodName + " of " + declaringType.get().getName() + " has no executable "
                + "method. It was resolved while the producer it disposes of was compiled, so the two were "
                + "compiled apart from one another"));
        return new Disposer(declaring, method, disposedParameter, staticMethod,
            disposer.booleanValue("publicMethod").orElse(false));
    }

    private void invokeStatic(Disposer disposer, Object bean, BeanDependencyGroup dependencies) {
        ExecutableMethod<?, ?> method = disposer.method();
        int disposedParameter = disposer.disposedParameter();
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = i == disposedParameter ? bean : resolve(arguments[i], dependencies);
        }
        invoke(method, null, parameters);
    }

    private void invoke(Disposer disposer, Object bean, BeanDependencyGroup dependencies) {
        ExecutableMethod<?, ?> method = disposer.method();
        int disposedParameter = disposer.disposedParameter();
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = i == disposedParameter ? bean : resolve(arguments[i], dependencies);
        }
        Object host = resolveDefinition(disposer.declaring(), dependencies);
        if (host instanceof io.micronaut.aop.InterceptedProxy<?> proxy && !disposer.publicMethod()) {
            // A non-public disposer is invoked on the contextual target; public methods retain interception.
            host = proxy.interceptedTarget();
        }
        invoke(method, host, parameters);
    }

    /**
     * The executable method of the disposer: the one of its erased parameter types, which tell overloads of one
     * name apart, or the one of its name for a producer compiled before the types were recorded. The types are
     * compared by name, so that none has to be loaded by name, which a native image would need metadata for.
     */
    private static Optional<? extends ExecutableMethod<?, ?>> findMethod(BeanDefinition<?> declaring,
                                                                          String methodName,
                                                                          String[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return declaring.findPossibleMethods(methodName).findFirst();
        }
        return declaring.findPossibleMethods(methodName)
            .filter(method -> hasParameterTypes(method, parameterTypes))
            .findFirst();
    }

    private static boolean hasParameterTypes(ExecutableMethod<?, ?> method, String[] parameterTypes) {
        Class<?>[] types = method.getArgumentTypes();
        if (types.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> component = types[i];
            int dimensions = 0;
            while (component.isArray()) {
                component = component.getComponentType();
                dimensions++;
            }
            if (!parameterTypes[i].equals(component.getName() + "[]".repeat(dimensions))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "NullAway"})
    private static void invoke(ExecutableMethod<?, ?> method, @Nullable Object target, Object[] parameters) {
        // the executable method of a static disposer is dispatched without reading the target at all, so
        // there is no instance to pass and none is expected
        ((ExecutableMethod<Object, ?>) method).invoke(target, parameters);
    }

    private static <T> T resolveDefinition(BeanDefinition<T> definition, BeanDependencyGroup dependencies) {
        return dependencies.getBean(definition, definition.asArgument());
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object resolve(Argument<?> argument, BeanDependencyGroup dependencies) {
        if (!argument.getAnnotationMetadata().hasStereotype(ResolveWith.class)) {
            return dependencies.getBean((Argument<Object>) argument, Qualifiers.forArgument((Argument<Object>) argument));
        }
        CdiBeanContainer container = this.container;
        if (container == null) {
            container = beanContext.getBean(CdiBeanContainer.class);
            this.container = container;
        }
        jakarta.enterprise.inject.spi.Bean<?> selected = container.resolve(container.beansOf(argument,
            CdiQualifier.declared(argument.getAnnotationMetadata())));
        if (selected == null) {
            if (argument.isNullable()) {
                return null;
            }
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException("No bean for disposer parameter " + argument);
        }
        BeanDefinition<Object> definition = (BeanDefinition<Object>) ((CdiBean<?>) selected).definition();
        return dependencies.getBean(definition, CdiInstance.askedAs((Argument<Object>) argument, definition));
    }

    /**
     * The disposer method of a producer, and the bean that declares it.
     *
     * @param declaring         The definition of the bean that declares the disposer
     * @param method            The disposer
     * @param disposedParameter The index of its {@code Disposes} parameter
     * @param staticMethod      Whether it is static, and invoked on no instance
     * @param publicMethod      Whether it is public, and invoked through a client proxy's interceptors
     */
    private record Disposer(BeanDefinition<?> declaring, ExecutableMethod<?, ?> method, int disposedParameter,
                            boolean staticMethod, boolean publicMethod) {
    }

    /**
     * A producer's definition compared by identity: two runtime definitions of one class are equal and are not
     * the same producer.
     *
     * @param definition The definition
     */
    private record ProducerKey(BeanDefinition<?> definition) {
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof ProducerKey key && key.definition == definition;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(definition);
        }
    }
}
