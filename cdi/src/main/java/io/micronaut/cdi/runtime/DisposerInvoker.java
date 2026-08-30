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

import io.micronaut.cdi.annotation.CdiDisposer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
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
        if (disposer.booleanValue("staticMethod").orElse(false)) {
            // a static disposer has no executable method — Micronaut writes none for a static method — and no
            // instance to be invoked on, so it is dispatched reflectively
            invokeStatic(declaringType.get(), methodName, disposedParameter, bean);
            return bean;
        }
        invoke(declaringType.get(), methodName, disposedParameter, bean);
        return bean;
    }

    private void invokeStatic(Class<?> declaringType, String methodName, int disposedParameter, Object bean) {
        java.lang.reflect.Method reflective = null;
        for (java.lang.reflect.Method candidate : declaringType.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)
                && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                && candidate.getParameterCount() > disposedParameter) {
                reflective = candidate;
                break;
            }
        }
        if (reflective == null) {
            throw new IllegalStateException("The static disposer method " + methodName + " of "
                + declaringType.getName() + " could not be found");
        }
        java.lang.reflect.Parameter[] reflectiveParameters = reflective.getParameters();
        Object[] parameters = new Object[reflectiveParameters.length];
        java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments = new java.util.ArrayList<>(2);
        for (int i = 0; i < reflectiveParameters.length; i++) {
            if (i == disposedParameter) {
                parameters[i] = bean;
                continue;
            }
            java.util.List<java.lang.annotation.Annotation> qualifiers = new java.util.ArrayList<>(1);
            for (java.lang.annotation.Annotation annotation : reflectiveParameters[i].getAnnotations()) {
                if (annotation.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                    qualifiers.add(annotation);
                }
            }
            Argument<Object> argument = (Argument<Object>) CdiTypes.<Object>argumentOf(
                reflectiveParameters[i].getParameterizedType());
            io.micronaut.context.BeanRegistration<Object> registration = beanContext.getBeanRegistration(
                argument, CdiQualifiers.of(qualifiers.toArray(new java.lang.annotation.Annotation[0])));
            if (CdiResolution.isDependent(registration.getBeanDefinition())) {
                transientArguments.add(registration);
            }
            parameters[i] = registration.bean();
        }
        try {
            reflective.setAccessible(true);
            reflective.invoke(null, parameters);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("The static disposer method " + methodName + " of "
                + declaringType.getName() + " is not accessible", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            close(transientArguments);
        }
    }

    private void invoke(Class<?> declaringType, String methodName, int disposedParameter, Object bean) {
        BeanDefinition<?> declaring = beanContext.getBeanDefinition(declaringType);
        ExecutableMethod<?, ?> method = declaring.findPossibleMethods(methodName)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("The disposer method " + methodName + " of "
                + declaringType.getName() + " has no executable method. It was resolved while the producer it "
                + "disposes of was compiled, so the two were compiled apart from one another"));
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments = new java.util.ArrayList<>(2);
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = i == disposedParameter ? bean : resolve(arguments[i], transientArguments);
        }
        if (!declaring.isSingleton()
            && !declaring.getAnnotationMetadata().hasStereotype(
            io.micronaut.cdi.annotation.CdiApplicationScope.class)
            && !declaring.getAnnotationMetadata().hasStereotype(
            io.micronaut.cdi.annotation.CdiRequestScope.class)) {
            // a dependent declaring bean exists for the one disposal: created for it, destroyed with its own
            // dependents when the disposer has run
            io.micronaut.context.BeanRegistration<?> registration = registrationOf(declaring);
            try {
                invoke(method, registration.bean(), parameters);
            } finally {
                registration.close();
                close(transientArguments);
            }
            return;
        }
        try {
            Object host = beanContext.getBean(declaring);
            if (host instanceof io.micronaut.aop.InterceptedProxy<?> proxy && !isPublic(method)) {
                // a disposer may be protected, which a client proxy does not delegate — but a public one is
                // invoked through the proxy, so that the interceptors bound to it interpose (section 2.7)
                host = proxy.interceptedTarget();
            }
            invoke(method, host, parameters);
        } finally {
            close(transientArguments);
        }
    }

    @SuppressWarnings("unchecked")
    private io.micronaut.context.BeanRegistration<?> registrationOf(BeanDefinition<?> declaring) {
        BeanDefinition<Object> definition = (BeanDefinition<Object>) declaring;
        return beanContext.getBeanRegistration(definition.asArgument(),
            new io.micronaut.context.Qualifier<Object>() {
                @Override
                public <BT extends io.micronaut.inject.BeanType<Object>> java.util.stream.Stream<BT> reduce(
                    Class<Object> beanType, java.util.stream.Stream<BT> candidates) {
                    return candidates.filter(candidate -> candidate == declaring || candidate.equals(declaring));
                }
            });
    }

    private static boolean isPublic(ExecutableMethod<?, ?> method) {
        java.lang.reflect.Method reflective = method.getTargetMethod();
        return reflective != null && java.lang.reflect.Modifier.isPublic(reflective.getModifiers());
    }

    @SuppressWarnings("unchecked")
    private static void invoke(ExecutableMethod<?, ?> method, Object target, Object[] parameters) {
        ((ExecutableMethod<Object, ?>) method).invoke(target, parameters);
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Argument<?> argument,
                           java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments) {
        Qualifier<Object> qualifier = (Qualifier<Object>) Qualifiers.<Object>forArgument(argument);
        io.micronaut.context.BeanRegistration<Object> registration =
            beanContext.getBeanRegistration((Argument<Object>) argument, qualifier);
        if (CdiResolution.isDependent(registration.getBeanDefinition())) {
            // a dependent argument exists for the one disposal, and is destroyed when it completes
            transientArguments.add(registration);
        }
        return registration.bean();
    }

    private static void close(java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments) {
        for (io.micronaut.context.BeanRegistration<?> registration : transientArguments) {
            registration.close();
        }
    }
}
