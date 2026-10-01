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

import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.cdi.internal.runtime.CdiAssignability;
import io.micronaut.cdi.internal.runtime.CdiBean;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiQualifier;
import io.micronaut.cdi.internal.runtime.ObserverRegistry;
import io.micronaut.cdi.internal.runtime.QualifierOverlay;
import io.micronaut.cdi.internal.extension.ExtensionContexts;
import io.micronaut.cdi.spi.PortableExtensions;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AfterTypeDiscovery;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBean;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Runs the portable extensions of an SE bootstrap over a container of compiled beans.
 *
 * <p>The beans exist before the container starts, so the events of the lifecycle (section 3.10.2) describe them
 * rather than discover them. The observer methods of an extension are found by reading its class, and the events
 * are fired in the order of the specification: {@code BeforeBeanDiscovery}; a {@code ProcessAnnotatedType} for
 * each bean class; {@code AfterTypeDiscovery}; for each bean its {@code ProcessInjectionTarget}, where it is a
 * managed bean, its {@code ProcessBeanAttributes} and its {@code ProcessBean}; {@code AfterBeanDiscovery}; and
 * {@code AfterDeploymentValidation}. What an extension can still add as the container starts - a qualifier on a
 * bean class, a context, an observer method - is registered with the container, and what would change a compiled
 * bean is refused by the event, with an {@code UnsupportedOperationException}.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ReflectivePortableExtensions implements PortableExtensions {

    @Override
    public void run(BeanContext context, Request request) {
        List<Extension> extensions = extensionsOf(request);
        if (extensions.isEmpty()) {
            return;
        }
        CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
        ExtensionContexts contexts = context.getBean(ExtensionContexts.class);
        for (Extension extension : extensions) {
            contexts.registerExtension(extension);
        }
        container.refreshCandidates();
        List<Observer> observers = new ArrayList<>();
        for (Extension extension : extensions) {
            observers.addAll(Observer.of(extension, container));
        }
        observers.sort(Comparator.comparingInt(Observer::priority));
        List<Throwable> problems = new ArrayList<>();

        fire(observers, new PortableEvents.BeforeDiscovery(contexts), Argument.of(BeforeBeanDiscovery.class),
            problems);

        QualifierOverlay overlay = context.getBean(QualifierOverlay.class);
        for (Class<?> type : beanClassesOf(applicationBeans(container, extensions))) {
            PortableEvents.AnnotatedTypeEvent<?> event = new PortableEvents.AnnotatedTypeEvent<>(type);
            fire(observers, event, Argument.of(ProcessAnnotatedType.class, Argument.of(type)), problems);
            for (Annotation added : event.added()) {
                overlay.add(type.getName(), CdiQualifier.ofInstance(added));
            }
        }
        // a bean reads its qualifiers once: the beans are read again, with what was added
        container.refreshCandidates();
        List<Bean<?>> beans = applicationBeans(container, extensions);

        List<Class<?>> alternatives = new ArrayList<>();
        for (Bean<?> bean : beans) {
            if (bean.isAlternative() && !alternatives.contains(bean.getBeanClass())) {
                alternatives.add(bean.getBeanClass());
            }
        }
        fire(observers, new PortableEvents.AfterTypes(alternatives), Argument.of(AfterTypeDiscovery.class), problems);

        for (Bean<?> bean : beans) {
            describe(bean, observers, problems);
        }

        fire(observers, new PortableEvents.AfterBeans(contexts, context.getBean(ObserverRegistry.class),
            container::isNormalScope, problems), Argument.of(AfterBeanDiscovery.class), problems);
        if (!problems.isEmpty()) {
            throw withTheRest(new DefinitionException(problems.get(0)), problems);
        }
        // a context or an observer an extension added is part of what the container answers with from here
        container.refreshCandidates();
        // the container validates the deployment before the extensions are told it did (section 11.5.4)
        container.validateDeployment();
        fire(observers, new PortableEvents.AfterValidation(problems), Argument.of(AfterDeploymentValidation.class),
            problems);
        if (!problems.isEmpty()) {
            throw withTheRest(new DeploymentException(problems.get(0)), problems);
        }
    }

    private static <E extends RuntimeException> E withTheRest(E failure, List<Throwable> problems) {
        for (int i = 1; i < problems.size(); i++) {
            failure.addSuppressed(problems.get(i));
        }
        return failure;
    }

    /**
     * The extensions of the bootstrap, one of each class: the instances it was handed, an instance of each class
     * it named, and what the service loader finds through the class loader it was given.
     */
    private static List<Extension> extensionsOf(Request request) {
        Map<Class<?>, Extension> extensions = new LinkedHashMap<>();
        for (Extension instance : request.instances()) {
            extensions.putIfAbsent(instance.getClass(), instance);
        }
        for (Class<? extends Extension> type : request.classes()) {
            if (!extensions.containsKey(type)) {
                extensions.put(type, instantiate(type));
            }
        }
        ServiceLoader.load(Extension.class, request.classLoader()).stream()
            .filter(provider -> request.admitted().test(provider.type().getName()))
            .filter(provider -> !extensions.containsKey(provider.type()))
            .forEach(provider -> extensions.put(provider.type(), provider.get()));
        return new ArrayList<>(extensions.values());
    }

    private static Extension instantiate(Class<? extends Extension> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw new DeploymentException("The extension " + type.getName() + " could not be created", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new DeploymentException("The extension " + type.getName() + " has no constructor without "
                + "parameters to create it with", e);
        }
    }

    /**
     * The beans of the application: every bean but the extensions themselves and what the container and
     * Micronaut bring of their own.
     */
    private static List<Bean<?>> applicationBeans(CdiBeanContainer container, List<Extension> extensions) {
        Set<Class<?>> extensionClasses = new LinkedHashSet<>();
        for (Extension extension : extensions) {
            extensionClasses.add(extension.getClass());
        }
        List<Bean<?>> beans = new ArrayList<>();
        for (Bean<?> bean : container.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            String name = beanClass.getName();
            if (!extensionClasses.contains(beanClass) && !name.startsWith("io.micronaut.")
                && !name.startsWith("jakarta.")) {
                beans.add(bean);
            }
        }
        return beans;
    }

    /**
     * The classes of the managed beans among the given beans, each once.
     */
    private static Set<Class<?>> beanClassesOf(List<Bean<?>> beans) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Bean<?> bean : beans) {
            if (producerOf(bean) == null) {
                types.add(bean.getBeanClass());
            }
        }
        return types;
    }

    private static @Nullable AnnotationValue<CdiProducer> producerOf(Bean<?> bean) {
        return bean instanceof CdiBean<?> compiled
            ? compiled.definition().getAnnotationMetadata().getAnnotation(CdiProducer.class) : null;
    }

    /**
     * Fires the events that describe one bean, in the order bean discovery has them (section 3.10.4.4).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void describe(Bean<?> bean, List<Observer> observers, List<Throwable> problems) {
        Class<?> beanClass = bean.getBeanClass();
        AnnotationValue<CdiProducer> producer = producerOf(bean);
        if (producer == null) {
            AnnotatedType<?> type = new ReflectiveAnnotatedType<>(beanClass);
            fire(observers, new PortableEvents.InjectionTargetEvent<>(beanClass, problems),
                Argument.of(ProcessInjectionTarget.class, Argument.of(beanClass)), problems);
            fire(observers, new PortableEvents.BeanAttributesEvent(bean, type, problems),
                Argument.of(ProcessBeanAttributes.class, Argument.of(beanClass)), problems);
            fire(observers, new PortableEvents.ManagedBeanEvent(bean, type, problems),
                Argument.of(ProcessManagedBean.class, Argument.of(beanClass)), problems);
            return;
        }
        // a produced bean: its attributes are of the type it produces, and the bean is of the class that
        // declares the producer
        Annotated annotated = producerOf((CdiBean<?>) bean, producer.stringValue("member").orElse(""),
            producer.booleanValue("field").orElse(false));
        Class<?> produced = ((CdiBean<?>) bean).definition().getBeanType();
        fire(observers, new PortableEvents.BeanAttributesEvent(bean, annotated, problems),
            Argument.of(ProcessBeanAttributes.class, Argument.of(produced)), problems);
        fire(observers, new PortableEvents.BeanEvent("ProcessBean", bean, annotated, problems),
            Argument.of(ProcessBean.class, Argument.of(beanClass)), problems);
    }

    /**
     * What is annotated where a bean is produced: the producer field. A producer method is not among what the
     * annotated model of this module describes, and asking for it says so.
     *
     * @param bean   The produced bean
     * @param member The name of the producer
     * @param field  Whether the producer is a field
     * @return The annotated producer field, or {@code null} for a producer method
     */
    private static @Nullable Annotated producerOf(CdiBean<?> bean, String member, boolean field) {
        if (!field) {
            return null;
        }
        for (Class<?> type = bean.getBeanClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(member)) {
                    return new ReflectiveAnnotatedField<>(candidate);
                }
            }
        }
        return null;
    }

    /**
     * Notifies the observers of an event, in the order of their priority. What an observer throws is a problem
     * of the deployment (section 3.9.5), except a refusal of this container's, which comes out as it is.
     */
    private static void fire(List<Observer> observers, PortableEvent event, Argument<?> eventType,
                             List<Throwable> problems) {
        try {
            for (Observer observer : observers) {
                if (observer.observes(eventType)) {
                    try {
                        observer.notify(event);
                    } catch (UnsupportedOperationException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        problems.add(e);
                    }
                }
            }
        } finally {
            event.delivered();
        }
    }

    /**
     * An observer method of an extension, found by reading the class of the extension.
     *
     * @param extension  The extension
     * @param method     The method
     * @param eventIndex The position of the event parameter
     * @param observed   The type the event parameter observes
     * @param priority   The priority the event parameter declares, or the default one
     * @param container  The container, which an observer may ask for beside the event
     */
    private record Observer(Extension extension, Method method, int eventIndex, Argument<?> observed, int priority,
                            CdiBeanContainer container) {

        private static final int DEFAULT_PRIORITY = jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;

        static List<Observer> of(Extension extension, CdiBeanContainer container) {
            List<Observer> found = new ArrayList<>();
            for (Class<?> type = extension.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    Annotation[][] annotations = method.getParameterAnnotations();
                    for (int i = 0; i < annotations.length; i++) {
                        if (!has(annotations[i], Observes.class)) {
                            continue;
                        }
                        Class<?> raw = method.getParameterTypes()[i];
                        if (!isLifecycleEvent(raw)) {
                            // an extension observing an event of the application, which this subset does not
                            // deliver to it, must not be left waiting for it silently
                            throw new UnsupportedOperationException("The observer method " + method + " of the "
                                + "extension observes " + raw.getName() + ", which is not one of the container "
                                + "lifecycle events a compile-time container fires to a portable extension");
                        }
                        requireNothingButTheContainer(method, i);
                        method.setAccessible(true);
                        int priority = DEFAULT_PRIORITY;
                        for (Annotation annotation : annotations[i]) {
                            if (annotation instanceof Priority declared) {
                                priority = declared.value();
                            }
                        }
                        found.add(new Observer(extension, method, i,
                            SpecificationTypes.argumentOf(method.getGenericParameterTypes()[i]), priority, container));
                    }
                }
            }
            return found;
        }

        private static boolean has(Annotation[] annotations, Class<? extends Annotation> type) {
            for (Annotation annotation : annotations) {
                if (type.isInstance(annotation)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isLifecycleEvent(Class<?> type) {
            return type == BeforeBeanDiscovery.class || type == ProcessAnnotatedType.class
                || type == AfterTypeDiscovery.class || type == ProcessInjectionTarget.class
                || type == ProcessBeanAttributes.class || type == ProcessBean.class
                || type == ProcessManagedBean.class || type == AfterBeanDiscovery.class
                || type == AfterDeploymentValidation.class;
        }

        private static void requireNothingButTheContainer(Method method, int eventIndex) {
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i != eventIndex && !BeanContainer.class.isAssignableFrom(types[i])) {
                    throw new DefinitionException("The observer method " + method + " of the extension takes a "
                        + types[i].getName() + ": beside the event, an observer of a container lifecycle event "
                        + "may only take the BeanManager");
                }
            }
        }

        boolean observes(Argument<?> eventType) {
            if (CdiAssignability.isEventTypeMatching(observed, eventType)) {
                return true;
            }
            // a managed bean's event is a ProcessBean as well, of the same class
            return eventType.getType() == ProcessManagedBean.class && CdiAssignability.isEventTypeMatching(observed,
                Argument.of(ProcessBean.class, (String) null, eventType.getTypeParameters()));
        }

        void notify(Object event) {
            Object[] arguments = new Object[method.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = i == eventIndex ? event : container;
            }
            try {
                method.invoke(Modifier.isStatic(method.getModifiers()) ? null : extension, arguments);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException thrown) {
                    throw thrown;
                }
                throw new DefinitionException("The observer method " + method + " of the extension failed",
                    e.getCause());
            } catch (IllegalAccessException e) {
                throw new DefinitionException("The observer method " + method + " of the extension cannot be "
                    + "invoked", e);
            }
        }
    }
}
