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
package io.micronaut.cdi.reflection;

import io.micronaut.cdi.runtime.CdiQualifier;
import io.micronaut.cdi.runtime.ObserverRegistry;
import io.micronaut.cdi.runtime.extension.ExtensionContexts;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AfterTypeDiscovery;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanAttributes;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.InjectionTarget;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBean;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.enterprise.inject.spi.configurator.AnnotatedConstructorConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedMethodConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import jakarta.enterprise.inject.spi.configurator.BeanAttributesConfigurator;
import jakarta.enterprise.inject.spi.configurator.BeanConfigurator;
import jakarta.enterprise.inject.spi.configurator.ObserverMethodConfigurator;
import jakarta.enterprise.invoke.Invoker;
import jakarta.enterprise.invoke.InvokerBuilder;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The events of the portable extension lifecycle, as a container of compiled beans fires them: each describes
 * what was compiled, takes what can still be added as the container starts - a qualifier on a bean class, a
 * context, an observer method, a problem - and refuses what would change a compiled bean.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class PortableEvents {

    private PortableEvents() {
    }

    /**
     * The first event: the deployment is about to be discovered.
     */
    static final class BeforeDiscovery extends PortableEvent implements BeforeBeanDiscovery {

        private final ExtensionContexts contexts;

        BeforeDiscovery(ExtensionContexts contexts) {
            super("BeforeBeanDiscovery");
            this.contexts = contexts;
        }

        @Override
        public void addQualifier(Class<? extends Annotation> qualifier) {
            whileNotifying();
            contexts.registerQualifier(qualifier.getName());
        }

        @Override
        public void addQualifier(AnnotatedType<? extends Annotation> qualifier) {
            throw refused("addQualifier(AnnotatedType)");
        }

        @Override
        public void addScope(Class<? extends Annotation> scopeType, boolean normal, boolean passivating) {
            throw refused("addScope");
        }

        @Override
        public void addStereotype(Class<? extends Annotation> stereotype, Annotation... stereotypeDef) {
            throw refused("addStereotype");
        }

        @Override
        public void addInterceptorBinding(AnnotatedType<? extends Annotation> bindingType) {
            throw refused("addInterceptorBinding");
        }

        @Override
        public void addInterceptorBinding(Class<? extends Annotation> bindingType, Annotation... bindingTypeDef) {
            throw refused("addInterceptorBinding");
        }

        @Override
        public void addAnnotatedType(AnnotatedType<?> type, String id) {
            throw refused("addAnnotatedType");
        }

        @Override
        public <T> AnnotatedTypeConfigurator<T> addAnnotatedType(Class<T> type, String id) {
            throw refused("addAnnotatedType");
        }

        @Override
        public <T extends Annotation> AnnotatedTypeConfigurator<T> configureQualifier(Class<T> qualifier) {
            throw refused("configureQualifier");
        }

        @Override
        public <T extends Annotation> AnnotatedTypeConfigurator<T> configureInterceptorBinding(Class<T> bindingType) {
            throw refused("configureInterceptorBinding");
        }
    }

    /**
     * A bean class is described. A qualifier may be added to it.
     *
     * @param <X> The class
     */
    static final class AnnotatedTypeEvent<X> extends PortableEvent implements ProcessAnnotatedType<X> {

        private final AnnotatedType<X> type;
        private final List<Annotation> added = new ArrayList<>();

        AnnotatedTypeEvent(Class<X> type) {
            super("ProcessAnnotatedType");
            this.type = new ReflectiveAnnotatedType<>(type);
        }

        /**
         * The qualifiers the observers added to the class.
         */
        List<Annotation> added() {
            return added;
        }

        @Override
        public AnnotatedType<X> getAnnotatedType() {
            whileNotifying();
            return type;
        }

        @Override
        public void setAnnotatedType(AnnotatedType<X> replacement) {
            throw refused("setAnnotatedType");
        }

        @Override
        public AnnotatedTypeConfigurator<X> configureAnnotatedType() {
            whileNotifying();
            return new TypeConfigurator<>(type, added);
        }

        @Override
        public void veto() {
            throw refused("veto");
        }
    }

    /**
     * Configures a bean class as far as a compiled one can be: by a qualifier added to it.
     *
     * @param <T> The class
     */
    private static final class TypeConfigurator<T> implements AnnotatedTypeConfigurator<T> {

        private static final String NAME = "AnnotatedTypeConfigurator";

        private final AnnotatedType<T> type;
        private final List<Annotation> added;

        TypeConfigurator(AnnotatedType<T> type, List<Annotation> added) {
            this.type = type;
            this.added = added;
        }

        @Override
        public AnnotatedType<T> getAnnotated() {
            return type;
        }

        @Override
        public AnnotatedTypeConfigurator<T> add(Annotation annotation) {
            if (!CdiQualifier.isQualifierType(annotation.annotationType())) {
                throw new UnsupportedOperationException("AnnotatedTypeConfigurator.add(@"
                    + annotation.annotationType().getName() + ") is not supported by a compile-time container: "
                    + "only a qualifier can be added to a bean class that was compiled. A build compatible "
                    + "extension can add any annotation while the class compiles");
            }
            added.add(annotation);
            return this;
        }

        @Override
        public AnnotatedTypeConfigurator<T> remove(Predicate<Annotation> predicate) {
            throw PortableEvent.refused(NAME, "remove");
        }

        @Override
        public Set<AnnotatedMethodConfigurator<? super T>> methods() {
            throw PortableEvent.refused(NAME, "methods");
        }

        @Override
        public Set<AnnotatedFieldConfigurator<? super T>> fields() {
            throw PortableEvent.refused(NAME, "fields");
        }

        @Override
        public Set<AnnotatedConstructorConfigurator<T>> constructors() {
            throw PortableEvent.refused(NAME, "constructors");
        }
    }

    /**
     * The types have been discovered.
     */
    static final class AfterTypes extends PortableEvent implements AfterTypeDiscovery {

        private final List<Class<?>> alternatives;

        AfterTypes(List<Class<?>> alternatives) {
            super("AfterTypeDiscovery");
            this.alternatives = List.copyOf(alternatives);
        }

        @Override
        public List<Class<?>> getAlternatives() {
            whileNotifying();
            // the alternatives that were selected as the application compiled, which the list cannot change
            return alternatives;
        }

        @Override
        public List<Class<?>> getInterceptors() {
            throw refused("getInterceptors");
        }

        @Override
        public List<Class<?>> getDecorators() {
            throw refused("getDecorators");
        }

        @Override
        public void addAnnotatedType(AnnotatedType<?> type, String id) {
            throw refused("addAnnotatedType");
        }

        @Override
        public <T> AnnotatedTypeConfigurator<T> addAnnotatedType(Class<T> type, String id) {
            throw refused("addAnnotatedType");
        }
    }

    /**
     * The class of a managed bean is about to be injected into, which here was compiled.
     *
     * @param <X> The class
     */
    static final class InjectionTargetEvent<X> extends PortableEvent implements ProcessInjectionTarget<X> {

        private final AnnotatedType<X> type;
        private final List<Throwable> problems;

        InjectionTargetEvent(Class<X> type, List<Throwable> problems) {
            super("ProcessInjectionTarget");
            this.type = new ReflectiveAnnotatedType<>(type);
            this.problems = problems;
        }

        @Override
        public AnnotatedType<X> getAnnotatedType() {
            whileNotifying();
            return type;
        }

        @Override
        public InjectionTarget<X> getInjectionTarget() {
            throw refused("getInjectionTarget");
        }

        @Override
        public void setInjectionTarget(InjectionTarget<X> injectionTarget) {
            throw refused("setInjectionTarget");
        }

        @Override
        public void addDefinitionError(Throwable t) {
            whileNotifying();
            problems.add(t);
        }
    }

    /**
     * The attributes of a bean are described.
     *
     * @param <T> The class of the bean
     */
    static final class BeanAttributesEvent<T> extends PortableEvent implements ProcessBeanAttributes<T> {

        private final Bean<T> bean;
        private final @org.jspecify.annotations.Nullable Annotated annotated;
        private final List<Throwable> problems;

        BeanAttributesEvent(Bean<T> bean, @org.jspecify.annotations.Nullable Annotated annotated,
                            List<Throwable> problems) {
            super("ProcessBeanAttributes");
            this.bean = bean;
            this.annotated = annotated;
            this.problems = problems;
        }

        @Override
        public Annotated getAnnotated() {
            whileNotifying();
            if (annotated == null) {
                throw new UnsupportedOperationException("The annotated producer method of " + bean + " is not "
                    + "described: the annotated model of micronaut-cdi-reflection has classes, fields and "
                    + "parameters");
            }
            return annotated;
        }

        @Override
        public BeanAttributes<T> getBeanAttributes() {
            whileNotifying();
            return bean;
        }

        @Override
        public void setBeanAttributes(BeanAttributes<T> beanAttributes) {
            throw refused("setBeanAttributes");
        }

        @Override
        public BeanAttributesConfigurator<T> configureBeanAttributes() {
            throw refused("configureBeanAttributes");
        }

        @Override
        public void addDefinitionError(Throwable t) {
            whileNotifying();
            problems.add(t);
        }

        @Override
        public void veto() {
            throw refused("veto");
        }

        @Override
        public void ignoreFinalMethods() {
            throw refused("ignoreFinalMethods");
        }
    }

    /**
     * A bean is about to be registered, which here was compiled.
     *
     * @param <X> The class of the bean
     */
    static class BeanEvent<X> extends PortableEvent implements ProcessBean<X> {

        private final Bean<X> bean;
        private final @org.jspecify.annotations.Nullable Annotated annotated;
        private final List<Throwable> problems;

        BeanEvent(String name, Bean<X> bean, @org.jspecify.annotations.Nullable Annotated annotated,
                  List<Throwable> problems) {
            super(name);
            this.bean = bean;
            this.annotated = annotated;
            this.problems = problems;
        }

        @Override
        public Annotated getAnnotated() {
            whileNotifying();
            if (annotated == null) {
                throw new UnsupportedOperationException("The annotated producer method of " + bean + " is not "
                    + "described: the annotated model of micronaut-cdi-reflection has classes, fields and "
                    + "parameters");
            }
            return annotated;
        }

        @Override
        public Bean<X> getBean() {
            whileNotifying();
            return bean;
        }

        @Override
        public void addDefinitionError(Throwable t) {
            whileNotifying();
            problems.add(t);
        }
    }

    /**
     * A managed bean is about to be registered.
     *
     * @param <X> The class of the bean
     */
    static final class ManagedBeanEvent<X> extends BeanEvent<X> implements ProcessManagedBean<X> {

        private final AnnotatedType<X> type;

        ManagedBeanEvent(Bean<X> bean, AnnotatedType<X> type, List<Throwable> problems) {
            super("ProcessManagedBean", bean, type, problems);
            this.type = type;
        }

        @Override
        public AnnotatedType<X> getAnnotatedBeanClass() {
            whileNotifying();
            return type;
        }

        @Override
        public InvokerBuilder<Invoker<X, ?>> createInvoker(AnnotatedMethod<? super X> method) {
            throw refused("createInvoker");
        }
    }

    /**
     * The beans have been discovered. A context and an observer method may be added.
     */
    static final class AfterBeans extends PortableEvent implements AfterBeanDiscovery {

        private final ExtensionContexts contexts;
        private final ObserverRegistry observers;
        private final Predicate<Class<? extends Annotation>> normalScope;
        private final List<Throwable> problems;

        AfterBeans(ExtensionContexts contexts, ObserverRegistry observers,
                   Predicate<Class<? extends Annotation>> normalScope, List<Throwable> problems) {
            super("AfterBeanDiscovery");
            this.contexts = contexts;
            this.observers = observers;
            this.normalScope = normalScope;
            this.problems = problems;
        }

        @Override
        public void addDefinitionError(Throwable t) {
            whileNotifying();
            problems.add(t);
        }

        @Override
        public void addBean(Bean<?> bean) {
            throw refused("addBean");
        }

        @Override
        public <T> BeanConfigurator<T> addBean() {
            throw refused("addBean");
        }

        @Override
        public void addObserverMethod(ObserverMethod<?> observerMethod) {
            whileNotifying();
            observers.registerSynthetic(observerMethod);
        }

        @Override
        public <T> ObserverMethodConfigurator<T> addObserverMethod() {
            throw refused("addObserverMethod()");
        }

        @Override
        public void addContext(Context context) {
            whileNotifying();
            contexts.register(context instanceof AlterableContext alterable ? alterable : new Unalterable(context),
                normalScope.test(context.getScope()));
        }

        @Override
        public <T> AnnotatedType<T> getAnnotatedType(Class<T> type, String id) {
            whileNotifying();
            return new ReflectiveAnnotatedType<>(type);
        }

        @Override
        public <T> Iterable<AnnotatedType<T>> getAnnotatedTypes(Class<T> type) {
            whileNotifying();
            return List.of(new ReflectiveAnnotatedType<>(type));
        }
    }

    /**
     * The deployment has been validated, which here happened as it compiled.
     */
    static final class AfterValidation extends PortableEvent implements AfterDeploymentValidation {

        private final List<Throwable> problems;

        AfterValidation(List<Throwable> problems) {
            super("AfterDeploymentValidation");
            this.problems = problems;
        }

        @Override
        public void addDeploymentProblem(Throwable t) {
            whileNotifying();
            problems.add(t);
        }
    }

    /**
     * A context that does not destroy single instances, held where the container holds alterable ones.
     *
     * @param delegate The context
     */
    private record Unalterable(Context delegate) implements AlterableContext {

        @Override
        public void destroy(Contextual<?> contextual) {
            throw new UnsupportedOperationException("The context of " + delegate.getScope().getName()
                + " is not an AlterableContext, and cannot destroy one instance");
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return delegate.getScope();
        }

        @Override
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return delegate.get(contextual, creationalContext);
        }

        @Override
        public <T> T get(Contextual<T> contextual) {
            return delegate.get(contextual);
        }

        @Override
        public boolean isActive() {
            return delegate.isActive();
        }
    }
}
