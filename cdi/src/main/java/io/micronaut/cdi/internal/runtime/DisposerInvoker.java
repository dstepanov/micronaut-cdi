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

    public DisposerInvoker(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Object onPreDestroy(BeanPreDestroyEvent<Object> event) {
        Object bean = event.getBean();
        BeanDefinition<Object> definition = event.getBeanDefinition();
        AnnotationValue<CdiDisposer> disposer = definition.getAnnotation(CdiDisposer.class);
        if (disposer == null) {
            return bean;
        }
        Optional<Class<?>> declaringType = disposer.classValue("declaringType");
        String methodName = disposer.stringValue("method").orElse(null);
        int disposedParameter = disposer.intValue("disposedParameter").orElse(-1);
        if (declaringType.isEmpty() || methodName == null || disposedParameter < 0) {
            return bean;
        }
        String[] parameterTypes = disposer.stringValues("parameterTypes");
        return event.withDependencies(dependencies -> {
            if (disposer.booleanValue("staticMethod").orElse(false)) {
                invokeStatic(declaringType.get(), methodName, parameterTypes, disposedParameter, bean, dependencies);
            } else {
                invoke(declaringType.get(), methodName, parameterTypes, disposedParameter, bean,
                    disposer.booleanValue("publicMethod").orElse(false), dependencies);
            }
            return bean;
        });
    }

    private void invokeStatic(Class<?> declaringType, String methodName, String[] parameterTypes,
                              int disposedParameter, Object bean, BeanDependencyGroup dependencies) {
        BeanDefinition<?> declaring = beanContext.getBeanDefinition(declaringType);
        ExecutableMethod<?, ?> method = findMethod(declaring, methodName, parameterTypes)
            .orElseThrow(() -> new IllegalStateException("The static disposer method " + methodName + " of "
                + declaringType.getName() + " has no executable method. It was resolved while the producer it "
                + "disposes of was compiled, so the two were compiled apart from one another"));
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = i == disposedParameter ? bean : resolve(arguments[i], dependencies);
        }
        invoke(method, null, parameters);
    }

    private void invoke(Class<?> declaringType, String methodName, String[] parameterTypes,
                        int disposedParameter, Object bean, boolean publicMethod, BeanDependencyGroup dependencies) {
        BeanDefinition<?> declaring = beanContext.getBeanDefinition(declaringType);
        ExecutableMethod<?, ?> method = findMethod(declaring, methodName, parameterTypes)
            .orElseThrow(() -> new IllegalStateException("The disposer method " + methodName + " of "
                + declaringType.getName() + " has no executable method. It was resolved while the producer it "
                + "disposes of was compiled, so the two were compiled apart from one another"));
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = i == disposedParameter ? bean : resolve(arguments[i], dependencies);
        }
        Object host = resolveDefinition(declaring, dependencies);
        if (host instanceof io.micronaut.aop.InterceptedProxy<?> proxy && !publicMethod) {
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
        return dependencies.getBean(definition.asArgument(), CdiInstance.only(definition));
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object resolve(Argument<?> argument, BeanDependencyGroup dependencies) {
        if (!argument.getAnnotationMetadata().hasStereotype(ResolveWith.class)) {
            return dependencies.getBean((Argument<Object>) argument, Qualifiers.forArgument((Argument<Object>) argument));
        }
        CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
        jakarta.enterprise.inject.spi.Bean<?> selected = container.resolve(container.beansOf(argument,
            CdiQualifier.declared(argument.getAnnotationMetadata())));
        if (selected == null) {
            if (argument.isNullable()) {
                return null;
            }
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException("No bean for disposer parameter " + argument);
        }
        BeanDefinition<Object> definition = (BeanDefinition<Object>) ((CdiBean<?>) selected).definition();
        return dependencies.getBean((Argument<Object>) argument, CdiInstance.only(definition));
    }
}
