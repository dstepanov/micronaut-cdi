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

import io.micronaut.cdi.annotation.CdiObserver;
import io.micronaut.context.BeanContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * One observer method, read from the executable method the annotation processor found and marked.
 *
 * <p>What it observes is read off the parameter it observes rather than from anything recorded beside it: the
 * parameter carries its type and its qualifiers, which is what the specification says an observer observes.
 * Notifying it is an invocation of the executable method Micronaut generated, on an instance of the bean that
 * declares it, with the event in the parameter's place and the other parameters resolved from the container as
 * the injection points they are.</p>
 *
 * @param <T> The observed event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiObserverMethod<T> implements ObserverMethod<T>, CdiNotifiable {

    private final BeanContext beanContext;
    private final BeanDefinition<?> declaring;
    private final ExecutableMethod<?, ?> method;
    private final int observedParameter;
    private final boolean async;
    private final boolean ifExists;
    private final boolean staticMethod;
    private final int priority;
    private final String during;

    CdiObserverMethod(BeanContext beanContext,
                      BeanDefinition<?> declaring,
                      ExecutableMethod<?, ?> method,
                      AnnotationValue<CdiObserver> observer) {
        this.beanContext = beanContext;
        this.declaring = declaring;
        this.method = method;
        this.observedParameter = observer.intValue("observedParameter").orElse(0);
        this.async = observer.booleanValue("async").orElse(false);
        this.ifExists = observer.booleanValue("ifExists").orElse(false);
        this.staticMethod = observer.booleanValue("staticMethod").orElse(false);
        this.priority = observer.intValue("priority").orElse(DEFAULT_PRIORITY);
        this.during = observer.stringValue("during").orElse("IN_PROGRESS");
    }

    /**
     * The parameter the observer observes, which carries both what it observes and how it is qualified.
     *
     * @return The observed parameter
     */
    public Argument<?> observed() {
        return method.getArguments()[observedParameter];
    }

    @Override
    public Class<?> getBeanClass() {
        if (declaring instanceof ProxyBeanDefinition<?> proxy) {
            return proxy.getTargetType();
        }
        return declaring.getBeanType();
    }

    @Override
    public Type getObservedType() {
        // the source of truth is the method itself: the compiled argument erases a wildcard or a variable,
        // and an inherited observer method keeps its declaring class's variables — the reflective signature
        // has both, and the bean class resolves the variables
        Type reflective = reflectiveObservedType();
        return reflective != null ? reflective : CdiTypes.requiredTypeOf(observed());
    }

    private @Nullable Type reflectiveObservedType() {
        Argument<?>[] arguments = method.getArguments();
        for (Class<?> declaringClass = getBeanClass(); declaringClass != null && declaringClass != Object.class;
             declaringClass = declaringClass.getSuperclass()) {
            for (java.lang.reflect.Method candidate : declaringClass.getDeclaredMethods()) {
                if (!candidate.getName().equals(method.getName())
                    || candidate.getParameterCount() != arguments.length) {
                    continue;
                }
                if (!sameErasure(candidate)) {
                    // an overload of the same name and arity is told apart by its raw parameter types
                    continue;
                }
                Type observed = candidate.getGenericParameterTypes()[observedParameter];
                java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution =
                    substitutionFor(getBeanClass(), declaringClass);
                return CdiTypes.substitute(observed, substitution);
            }
        }
        return null;
    }

    private boolean sameErasure(java.lang.reflect.Method candidate) {
        Argument<?>[] arguments = method.getArguments();
        Class<?>[] parameterTypes = candidate.getParameterTypes();
        for (int i = 0; i < arguments.length; i++) {
            Class<?> compiled = arguments[i].getType();
            Class<?> reflective = parameterTypes[i];
            // exact erasure, or the compiled erasure of a type variable widened to its bound: mutual
            // assignability alone would let on(String) answer for a sibling on(CharSequence) overload
            if (reflective != compiled && !reflective.isAssignableFrom(compiled)) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the bean class says the variables of the given superclass are: the closure carries the bean's own
     * arguments into every supertype, and the supertype's variables map onto what arrived there.
     */
    private static java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitutionFor(
        Class<?> beanClass, Class<?> declaringClass) {
        java.util.Map<java.lang.reflect.TypeVariable<?>, Type> substitution = new java.util.HashMap<>();
        if (beanClass == declaringClass) {
            return substitution;
        }
        for (Type supertype : CdiTypes.closureOf(CdiParameterizedType.of(beanClass))) {
            if (CdiTypes.rawClassOf(supertype) == declaringClass
                && supertype instanceof java.lang.reflect.ParameterizedType parameterized) {
                java.lang.reflect.TypeVariable<?>[] variables = declaringClass.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    substitution.put(variables[i], arguments[i]);
                }
                break;
            }
        }
        return substitution;
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return CdiQualifiers.declared(observed().getAnnotationMetadata());
    }

    @Override
    public Reception getReception() {
        return ifExists ? Reception.IF_EXISTS : Reception.ALWAYS;
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        // there are no transactions in CDI Lite, so every observer is notified as the event fires — but the
        // phase it asked for is still what it declared
        return TransactionPhase.valueOf(during);
    }

    @Override
    public jakarta.enterprise.inject.spi.Bean<?> getDeclaringBean() {
        return beanContext.getBean(CdiBeanContainer.class).canonicalBean(declaring);
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isAsync() {
        return async;
    }

    @Override
    public void notify(T event) {
        notify(event, new CdiEventMetadata(getObservedQualifiers(), null, getObservedType()));
    }

    /**
     * Notifies the observer of an event, with the metadata of the firing.
     *
     * @param event    The event
     * @param metadata What the observer may ask about the firing
     */
    @SuppressWarnings("unchecked")
    @Override
    public void notifyWith(Object event, jakarta.enterprise.inject.spi.EventMetadata metadata) {
        notify((T) event, metadata);
    }

    void notify(T event, jakarta.enterprise.inject.spi.EventMetadata metadata) {
        Object target = null;
        io.micronaut.context.BeanRegistration<?> transientTarget = null;
        if (!staticMethod) {
            // a static observer method is notified without an instance of the bean that declares it, which is
            // what the specification allows and what the executable method Micronaut generated for it expects
            if (ifExists && !exists()) {
                // the observer is notified only if an instance of its bean exists already, and none does
                return;
            }
            if (!ifExists && !contextIsActive()) {
                // a bean in a normal scope is reachable only while its context is active; while it is not,
                // the observer is simply not notified
                return;
            }
            if (isDependent()) {
                // a dependent observer bean exists for the one notification: it is created for it, and it and
                // everything created along with it are destroyed when the notification completes
                transientTarget = registrationOfDeclaring();
                target = transientTarget.bean();
            } else {
                target = beanContext.getBean(declaring);
                if (target instanceof io.micronaut.aop.InterceptedProxy<?> proxy) {
                    // the observer method may be protected, which a client proxy does not delegate: the
                    // notification goes to the instance the context holds
                    target = proxy.interceptedTarget();
                }
            }
        }
        Argument<?>[] arguments = method.getArguments();
        Object[] parameters = new Object[arguments.length];
        java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments = new java.util.ArrayList<>(2);
        // the try begins before the parameters resolve: a parameter that fails to resolve must not leave the
        // dependent observer instance — or the parameters resolved before it — undestroyed
        try {
            for (int i = 0; i < arguments.length; i++) {
                if (i == observedParameter) {
                    parameters[i] = event;
                } else if (arguments[i].getType() == jakarta.enterprise.inject.spi.EventMetadata.class) {
                    // the metadata of the firing is supplied by the notification rather than resolved
                    parameters[i] = metadata;
                } else {
                    parameters[i] = resolve(arguments[i], transientArguments);
                }
            }
            invoke(target, parameters);
        } finally {
            RuntimeException destructionFailure = null;
            if (transientTarget != null) {
                try {
                    destroy(transientTarget);
                } catch (RuntimeException e) {
                    destructionFailure = e;
                }
            }
            for (io.micronaut.context.BeanRegistration<?> registration : transientArguments) {
                try {
                    destroy(registration);
                } catch (RuntimeException e) {
                    if (destructionFailure == null) {
                        destructionFailure = e;
                    } else {
                        destructionFailure.addSuppressed(e);
                    }
                }
            }
            if (destructionFailure != null) {
                throw destructionFailure;
            }
        }
    }

    private boolean isDependent() {
        return !declaring.isSingleton()
            && !declaring.getAnnotationMetadata().hasStereotype(
            io.micronaut.cdi.annotation.CdiApplicationScope.class)
            && !declaring.getAnnotationMetadata().hasStereotype(
            io.micronaut.cdi.annotation.CdiRequestScope.class);
    }

    @SuppressWarnings("unchecked")
    private io.micronaut.context.BeanRegistration<?> registrationOfDeclaring() {
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

    @SuppressWarnings({"unchecked", "NullAway"})
    private void invoke(@Nullable Object target, Object[] parameters) {
        // a static observer method is dispatched without reading the target at all, so there is no instance to
        // pass and none is expected
        try {
            ((ExecutableMethod<Object, ?>) method).invoke(target, parameters);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            // section 2.8.5: a checked exception an observer throws is wrapped and rethrown
            throw new jakarta.enterprise.event.ObserverException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean exists() {
        // section 2.8.2: a conditional observer is notified only if an instance of its bean already exists in
        // a context that is active — an inactive context holds nothing reachable, and is not an error
        String scope = declaring.getAnnotationMetadata()
            .stringValue("io.micronaut.cdi.annotation.CdiScope").orElse(null);
        Class<?> held = getBeanClass();
        if ("jakarta.enterprise.context.RequestScoped".equals(scope)) {
            io.micronaut.cdi.context.RequestScope requestScope =
                beanContext.getBean(io.micronaut.cdi.context.RequestScope.class);
            return requestScope.isActive() && requestScope.holdsInstanceOf(held);
        }
        if ("jakarta.enterprise.context.ApplicationScoped".equals(scope)) {
            return beanContext.getBean(io.micronaut.cdi.context.ApplicationScope.class)
                .holdsInstanceOf(held);
        }
        BeanDefinition<Object> definition = (BeanDefinition<Object>) declaring;
        return beanContext.containsBean(definition.asArgument(), definition.getDeclaredQualifier());
    }

    /**
     * Whether the context the declaring bean lives in is active: an observer of a request-scoped bean is not
     * notified outside a request.
     */
    private boolean contextIsActive() {
        String scope = declaring.getAnnotationMetadata()
            .stringValue("io.micronaut.cdi.annotation.CdiScope").orElse(null);
        if ("jakarta.enterprise.context.RequestScoped".equals(scope)) {
            return beanContext.getBean(io.micronaut.cdi.context.RequestScope.class).isActive();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private <B> void destroy(io.micronaut.context.BeanRegistration<B> registration) {
        // registration.close() is a no-op for a definition with nothing of its own to dispose; destruction that
        // has to reach the pre-destroy listeners — the disposer methods of section 3.3.4 — goes through the
        // context
        beanContext.destroyBean(registration);
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Argument<?> argument,
                           java.util.List<io.micronaut.context.BeanRegistration<?>> transientArguments) {
        Qualifier<Object> qualifier = (Qualifier<Object>) Qualifiers.<Object>forArgument(argument);
        io.micronaut.context.BeanRegistration<Object> registration =
            beanContext.getBeanRegistration((Argument<Object>) argument, qualifier);
        if (CdiResolution.isDependent(registration.getBeanDefinition())) {
            // a dependent argument exists for the one notification, and is destroyed when it completes
            transientArguments.add(registration);
        }
        return registration.bean();
    }

    @Override
    public String toString() {
        return "Observer[" + getBeanClass().getName() + "#" + method.getName() + "]";
    }
}
