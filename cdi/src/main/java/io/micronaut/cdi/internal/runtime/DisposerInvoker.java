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

import io.micronaut.aop.InterceptedProxy;
import io.micronaut.cdi.internal.metadata.CdiDisposer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanDependencyGroup;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import io.micronaut.context.exceptions.NoSuchBeanException;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<BeanDefinition<?>, Disposer> disposers =
        new ConcurrentHashMap<>();
    private volatile @Nullable CdiBeanContainer container;

    public DisposerInvoker(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Object onPreDestroy(BeanPreDestroyEvent<Object> event) {
        Object bean = event.getBean();
        BeanDefinition<Object> definition = event.getBeanDefinition();
        Disposer found = disposers.get(definition);
        if (found == null) {
            AnnotationValue<CdiDisposer> disposer = definition.getAnnotation(CdiDisposer.class);
            if (disposer == null) {
                // every bean of the context is destroyed through here, and few of them were produced
                return bean;
            }
            found = disposerOf(beanContext, disposer);
            if (found == null) {
                return bean;
            }
            disposers.putIfAbsent(definition, found);
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

    /**
     * The disposer a producer's record names: the method of the declaring bean with the recorded name and
     * parameter types, which is the one invoked as the product is destroyed and the one whose parameters are
     * validated as the container starts.
     *
     * @param beanContext The context the declaring bean is defined in
     * @param disposer    The record
     * @return The disposer, or {@code null} where the record names no disposed parameter
     * @throws IllegalStateException Where the declaring bean has no executable method of the recorded signature
     * @throws io.micronaut.context.exceptions.NoSuchBeanException   Where no bean is of the declaring class
     * @throws io.micronaut.context.exceptions.NonUniqueBeanException Where more than one bean is of it
     */
    static @Nullable Disposer disposerOf(BeanContext beanContext, AnnotationValue<CdiDisposer> disposer) {
        Optional<Class<?>> declaringType = disposer.classValue("declaringType");
        String methodName = disposer.stringValue("method").orElse(null);
        int disposedParameter = disposer.intValue("disposedParameter").orElse(-1);
        if (declaringType.isEmpty() || methodName == null || disposedParameter < 0) {
            return null;
        }
        boolean staticMethod = disposer.booleanValue("staticMethod").orElse(false);
        // the bean of the declaring class itself: a selected alternative that extends it is another bean
        BeanDefinition<?> declaring = CdiResolution.declaringBeanOf(
            beanContext.getBeanDefinitions(declaringType.get()), declaringType.get().getName());
        if (declaring == null) {
            throw new NoSuchBeanException(declaringType.get());
        }
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
        if (host instanceof InterceptedProxy<?> proxy && !disposer.publicMethod()) {
            // A non-public disposer is invoked on the contextual target; public methods retain interception.
            host = proxy.interceptedTarget();
        }
        invoke(method, host, parameters);
    }

    /**
     * The executable method of the disposer: the one of its erased parameter types, which tell overloads of one
     * name apart, or the one of its name for a producer compiled before the types were recorded - a disposer has
     * its disposed parameter, so a record without types is one of those.
     */
    private static Optional<? extends ExecutableMethod<?, ?>> findMethod(BeanDefinition<?> declaring,
                                                                          String methodName,
                                                                          String[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return declaring.findPossibleMethods(methodName).findFirst();
        }
        return Optional.ofNullable(CdiTypes.executableMethodOf(declaring, methodName, parameterTypes));
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
        if (CdiResolution.isSuppliedByAnApplicationProvider(argument.getAnnotationMetadata())) {
            // a provider of the application's own supplies it, as it does a field of a bean
            return CdiResolution.fromApplicationProvider(beanContext, null, argument);
        }
        if (!CdiResolution.isResolvedByTheContainer(argument.getAnnotationMetadata())) {
            return dependencies.getBean((Argument<Object>) argument, Qualifiers.forArgument((Argument<Object>) argument));
        }
        CdiBeanContainer container = this.container;
        if (container == null) {
            container = beanContext.getBean(CdiBeanContainer.class);
            this.container = container;
        }
        CdiBeanContainer.Selection selection = container.selectionFor(argument);
        if (selection == CdiBeanContainer.Selection.MICRONAUT) {
            // no bean of the specification is a candidate: Micronaut's rules among the beans of Micronaut's own, as
            // at a bean's own point
            return dependencies.getBean((Argument<Object>) argument,
                CdiBeanContainer.Selection.micronautQualifier((Argument<Object>) argument, null));
        }
        BeanDefinition<Object> definition = (BeanDefinition<Object>) selection.definition();
        if (definition == null) {
            if (argument.isNullable()) {
                return null;
            }
            throw new UnsatisfiedResolutionException("No bean for disposer parameter " + argument);
        }
        Argument<Object> askedAs = CdiInstance.askedAs(beanContext, (Argument<Object>) argument, definition);
        // a dependent producer may satisfy it with null, which the disposal destroys as it does any other dependent
        Object bean = dependencies.getBeanRegistration(definition, askedAs).bean();
        if (bean == null && argument.getType().isPrimitive()) {
            // section 2.5.2.4: a producer that returns null satisfies a primitive parameter with its default value,
            // as it does a field
            return CdiResolutionCustomizer.primitiveDefaultOf(argument.getType());
        }
        return bean;
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
    record Disposer(BeanDefinition<?> declaring, ExecutableMethod<?, ?> method, int disposedParameter,
                    boolean staticMethod, boolean publicMethod) {
    }
}
