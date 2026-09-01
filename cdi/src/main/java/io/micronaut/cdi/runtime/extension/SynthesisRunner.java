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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.cdi.runtime.CdiInstance;
import io.micronaut.cdi.runtime.CdiQualifiers;
import io.micronaut.context.BeanContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Context;
import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.inject.Singleton;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Runs the synthesis phase of the build compatible extensions of section 2.10, and registers the beans they
 * describe.
 *
 * <p>The other phase this module implements, the enhancement of section 2.10.3, runs while the classes it
 * enhances are compiled, because that is where the classes are. This one runs as the container starts, because
 * that is where the beans are: a synthetic bean is described rather than written, and what describes it is a
 * creator the container instantiates and asks for the instance. Micronaut has bean definitions that are built
 * rather than generated, and one of those is registered for each bean an extension described.</p>
 *
 * <p>It runs as early as anything can: registering a bean changes the object graph, so it has to happen before
 * anything has been resolved from it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Context
@Order(Ordered.HIGHEST_PRECEDENCE)
@Internal
public final class SynthesisRunner {

    private static volatile @io.micronaut.core.annotation.Nullable List<BuildCompatibleExtension> overriddenExtensions;

    private final BeanContext beanContext;
    private final java.util.Map<BeanDefinition<?>, SyntheticBean<?>> described =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The lookup handed to each synthetic instance's creation function, kept until the instance is destroyed:
     * the dependent instances it obtained are the created instance's own (section 2.10.5).
     */
    private final java.util.Map<Object, CdiInstance<Object>> creatorLookups =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    public SynthesisRunner(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    /**
     * Hands the runner the extension instances of one deployment, in place of the service loading it does on
     * its own. Section 2.10 gives every extension one instance across all of its phases, and a harness that
     * runs the earlier phases itself — the kit's, which compiles a deployment at a time — hands the same
     * instances here so the later phases see what the earlier ones did. Setting {@code null} puts the service
     * loading back.
     *
     * @param extensions The extensions, or {@code null} to load them as usual
     */
    public static void overrideExtensions(
        @io.micronaut.core.annotation.Nullable List<BuildCompatibleExtension> extensions) {
        overriddenExtensions = extensions;
    }

    @PostConstruct
    void synthesise() {
        ClassLoader deploymentLoader = beanContext.getClassLoader() != null
            ? beanContext.getClassLoader() : SynthesisRunner.class.getClassLoader();
        CdiSyntheticComponents components = new CdiSyntheticComponents(deploymentLoader);
        RuntimeMessages messages = new RuntimeMessages();
        List<BuildCompatibleExtension> extensions = new ArrayList<>();
        List<BuildCompatibleExtension> overridden = overriddenExtensions;
        if (overridden != null) {
            extensions.addAll(overridden);
        } else {
            // the context's own loader, so that a deployment with a loader of its own — the kit's archives
            // have one — has its extension service entries seen
            ClassLoader classLoader = beanContext.getClassLoader() != null
                ? beanContext.getClassLoader() : SynthesisRunner.class.getClassLoader();
            ServiceLoader.load(BuildCompatibleExtension.class, classLoader)
                .forEach(extensions::add);
        }
        if (extensions.isEmpty()) {
            return;
        }
        // the synthesis phase describes what the container is to have, and every extension has its say before
        // any of it exists
        run(extensions, Synthesis.class, components, messages);
        failIfAnythingWasReported(messages);
        List<BeanDefinition<?>> registered = new ArrayList<>();
        for (SyntheticBean<?> bean : components.described()) {
            if (isDisabledAlternative(bean)) {
                // an alternative no priority selected is not enabled, exactly as a compiled one is not
                continue;
            }
            registered.add(register(bean));
        }
        io.micronaut.cdi.runtime.ObserverRegistry observerRegistry =
            beanContext.getBean(io.micronaut.cdi.runtime.ObserverRegistry.class);
        for (SyntheticObserverDescription<?> observer : components.describedObservers()) {
            observerRegistry.registerSynthetic(new SyntheticObserverMethod<>(beanContext, observer));
        }
        if (!registered.isEmpty()) {
            // the container may have read its beans already: what was just registered has to be seen
            beanContext.findBean(io.micronaut.cdi.runtime.CdiBeanContainer.class)
                .ifPresent(io.micronaut.cdi.runtime.CdiBeanContainer::refreshCandidates);
        }
        // the beans the compiler saw have already been described to the registration phase, as they were
        // compiled; what is left is the ones it did not see, which are the synthetic ones just registered
        registration(extensions, registered, messages);
        failIfAnythingWasReported(messages);
        // an invoker an extension built and handed to a synthetic component names lookups; whether they
        // resolve is a property of the deployment, and is checked as it comes up rather than at the first
        // invocation (CDI 4.1, chapter 7)
        for (SyntheticBean<?> bean : components.described()) {
            for (Object parameter : bean.parameters().values()) {
                validateInvokerLookups(parameter);
            }
        }
        // and the validation phase is the last word on it
        run(extensions, Validation.class, components, messages);
        failIfAnythingWasReported(messages);
    }

    private void validateInvokerLookups(@io.micronaut.core.annotation.Nullable Object parameter) {
        if (parameter instanceof io.micronaut.cdi.runtime.RecordedInvoker invoker) {
            invoker.validateLookups(beanContext);
        } else if (parameter instanceof Object[] values) {
            for (Object value : values) {
                validateInvokerLookups(value);
            }
        }
    }

    /**
     * Runs the registration phase, where an application has asked for it.
     *
     * <p>Describing every bean of the container in the terms of the language model means reading the classes back
     * with reflection, which is not something this module does of its own accord. An application that uses the
     * phase adds the module that can, and this is where that module is looked for; an extension that asks for the
     * phase without it is told what to add rather than quietly not running.</p>
     */
    private void registration(List<BuildCompatibleExtension> extensions,
                              List<BeanDefinition<?>> synthetic,
                              Messages messages) {
        if (!declaresRegistration(extensions)) {
            return;
        }
        List<BeanDefinition<?>> described = new ArrayList<>(synthetic);
        // the built-in beans are beans of the application too (section 2.10.3): the compiler never sees them,
        // so the phase is told about them here
        beanContext.findBeanDefinition(io.micronaut.cdi.runtime.CdiBeanContainer.class)
            .ifPresent(described::add);
        if (described.isEmpty()) {
            return;
        }
        beanContext.findBean(RegistrationPhase.class)
            .ifPresent(phase -> phase.run(extensions, described, messages));
    }

    private static boolean declaresRegistration(List<BuildCompatibleExtension> extensions) {
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : extension.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Registration.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs the methods of one phase, across every extension, in no particular order: the specification orders
     * the phases rather than the extensions within one.
     */
    private void run(List<BuildCompatibleExtension> extensions,
                     Class<? extends Annotation> phase,
                     SyntheticComponents components,
                     Messages messages) {
        record PhaseMethod(BuildCompatibleExtension extension, Method method) {
            private int priority() {
                jakarta.annotation.Priority priority = method.getAnnotation(jakarta.annotation.Priority.class);
                return priority != null ? priority.value()
                    : jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;
            }
        }
        List<PhaseMethod> methods = new ArrayList<>();
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : extension.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(phase)) {
                    method.setAccessible(true);
                    methods.add(new PhaseMethod(extension, method));
                }
            }
        }
        methods.sort(java.util.Comparator.comparingInt(PhaseMethod::priority));
        for (PhaseMethod phaseMethod : methods) {
            invoke(phaseMethod.extension(), phaseMethod.method(), components, messages);
        }
    }

    /**
     * Stops the container from starting when an extension reported an error, which is what the specification
     * has a deployment with an error in it do.
     */
    private static void failIfAnythingWasReported(RuntimeMessages messages) {
        if (!messages.errors().isEmpty()) {
            throw new IllegalStateException("A build compatible extension reported errors while the container was "
                + "starting: " + String.join("; ", messages.errors()));
        }
    }

    private void invoke(BuildCompatibleExtension extension,
                        Method method,
                        SyntheticComponents components,
                        Messages messages) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(Messages.class)) {
                arguments[i] = messages;
            } else if (parameterTypes[i].equals(SyntheticComponents.class)) {
                arguments[i] = components;
            } else if (parameterTypes[i].equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                arguments[i] = beanContext
                    .findBean(jakarta.enterprise.inject.build.compatible.spi.Types.class)
                    .orElseThrow(() -> new IllegalStateException("The method " + method + " asks for Types, "
                        + "which describing classes reflectively provides: add the micronaut-cdi-reflection "
                        + "module"));
            } else {
                throw new IllegalStateException("The synthesis method " + method + " asks for a "
                    + parameterTypes[i].getName() + ", which this module does not hand to one");
            }
        }
        try {
            method.invoke(extension, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("The synthesis method " + method + " could not be invoked", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("The synthesis method " + method + " failed", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> BeanDefinition<T> register(SyntheticBean<T> bean) {
        RuntimeBeanDefinition.Builder<T> builder = RuntimeBeanDefinition
            .builder(Argument.of(bean.implementationClass()),
                resolutionContext -> create(bean, resolutionContext));
        // a bean that names no qualifier has the default one, which the rule of section 2.1.3 says of a bean
        // an extension describes as much as of one a class declares
        List<Annotation> qualifiers = new ArrayList<>(bean.qualifiers());
        if (qualifiers.isEmpty()) {
            qualifiers.add(Default.Literal.INSTANCE);
        }
        builder.qualifier((io.micronaut.context.Qualifier<T>) CdiQualifiers
            .of(qualifiers.toArray(new Annotation[0])));
        builder.annotationMetadata(metadataOf(qualifiers, bean));
        if (bean.name() != null) {
            builder.named(bean.name());
        }
        Class<? extends Annotation> scope = scopeOf(bean);
        if (scope != null && scope.getName().equals("jakarta.inject.Singleton")) {
            builder.singleton(true);
            builder.scope(Singleton.class);
        } else if (scope != null && !"jakarta.enterprise.context.Dependent".equals(scope.getName())) {
            // routed to the scope's own context — the application scope's, the request scope's, or the one an
            // extension registered — so that the instance lives and dies with the context rather than being
            // held as a raw singleton or created afresh for every asker
            builder.singleton(false);
            builder.scope(contextScopeOf(scope));
        } else {
            // the dependent default of section 2.10.5: created afresh for whoever asks
            builder.singleton(false);
        }
        // the bean types are exactly what was declared (or the API's {Object} default) — the implementation
        // class is not among them unless the extension said so
        builder.exposedTypes(bean.types().toArray(new Class<?>[0]));
        RuntimeBeanDefinition<T> definition = builder.build();
        beanContext.registerBeanDefinition(definition);
        described.put(definition, bean);
        return definition;
    }

    /**
     * The scope of the synthetic bean: the one the extension set, or the one a stereotype it named carries.
     */
    private static @io.micronaut.core.annotation.Nullable Class<? extends Annotation> scopeOf(
        SyntheticBean<?> bean) {
        if (bean.scope() != null) {
            return bean.scope();
        }
        for (Class<? extends Annotation> stereotype : bean.stereotypes()) {
            for (Annotation carried : stereotype.getAnnotations()) {
                if (carried.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                    || carried.annotationType()
                        .isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    return carried.annotationType();
                }
            }
        }
        return null;
    }

    /**
     * The scope annotation whose context holds the bean: the specification's built-in scopes are served by
     * this module's own contexts, and a scope an extension registered is served by the context it registered.
     */
    private static Class<? extends Annotation> contextScopeOf(Class<? extends Annotation> scope) {
        return switch (scope.getName()) {
            case "jakarta.enterprise.context.ApplicationScoped" ->
                io.micronaut.cdi.annotation.CdiApplicationScope.class;
            case "jakarta.enterprise.context.RequestScoped" ->
                io.micronaut.cdi.annotation.CdiRequestScope.class;
            default -> scope;
        };
    }

    /**
     * Whether the synthetic bean is an enabled alternative, a disabled one, or no alternative at all: section
     * 2.1.7 enables an alternative by a priority, and one without a priority is not a bean of the deployment.
     */
    private static boolean isDisabledAlternative(SyntheticBean<?> bean) {
        boolean alternative = bean.alternative()
            || bean.stereotypes().stream().anyMatch(stereotype ->
                stereotype.isAnnotationPresent(jakarta.enterprise.inject.Alternative.class));
        return alternative && bean.priority() == null;
    }

    /**
     * What the extension said about the given definition, when the definition is a synthetic bean's.
     *
     * @param definition A definition
     * @return The description, or {@code null}
     */
    public @io.micronaut.core.annotation.Nullable SyntheticBean<?> describedBeanOf(BeanDefinition<?> definition) {
        return described.get(definition);
    }

    /**
     * Disposes of an instance of a synthetic bean the way the extension said to (section 2.10.5).
     *
     * @param bean     The description
     * @param instance The instance
     * @param <T>      The bean type
     */
    @SuppressWarnings("unchecked")
    public <T> void dispose(SyntheticBean<T> bean, Object instance) {
        try {
            if (bean.disposer() != null) {
                jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer<T> disposer;
                try {
                    disposer = bean.disposer().getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("The disposer " + bean.disposer().getName()
                        + " could not be instantiated", e);
                }
                CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
                try {
                    disposer.dispose((T) instance, lookup, new CdiParameters(bean.parameters()));
                } finally {
                    // what the disposal function looked up as a dependent instance lives only as long as
                    // the disposal itself (section 2.10.5)
                    lookup.destroyTransients();
                }
            }
        } finally {
            CdiInstance<Object> creatorLookup = creatorLookups.remove(instance);
            if (creatorLookup != null) {
                // and what the creation function looked up belongs to the instance it created, gone with it
                creatorLookup.destroyTransients();
            }
        }
    }

    /**
     * The annotations a synthetic bean carries, so that the container can report what qualifies it and what
     * scope it is in the way it reports them for any other bean.
     */
    private static AnnotationMetadata metadataOf(List<Annotation> qualifiers, SyntheticBean<?> bean) {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        for (Annotation qualifier : qualifiers) {
            metadata.addDeclaredAnnotation(qualifier.annotationType().getName(), membersOf(qualifier));
            metadata.addDeclaredStereotype(List.of(qualifier.annotationType().getName()),
                AnnotationUtil.QUALIFIER, Map.of());
        }
        for (Class<? extends Annotation> stereotype : bean.stereotypes()) {
            metadata.addDeclaredAnnotation(stereotype.getName(), Map.of());
            if (stereotype.isAnnotationPresent(jakarta.enterprise.inject.Alternative.class)) {
                metadata.addDeclaredStereotype(List.of(stereotype.getName()),
                    "jakarta.enterprise.inject.Alternative", Map.of());
            }
        }
        if (bean.priority() != null) {
            // the priority selects and ranks an alternative; it is carried as itself and as the order,
            // negated, the same way the compiler writes it for a selected alternative
            metadata.addDeclaredAnnotation("jakarta.annotation.Priority",
                Map.of(AnnotationMetadata.VALUE_MEMBER, bean.priority()));
            metadata.addDeclaredAnnotation("io.micronaut.core.annotation.Order",
                Map.of(AnnotationMetadata.VALUE_MEMBER, -bean.priority()));
            metadata.addDeclaredAnnotation("io.micronaut.context.annotation.Primary", Map.of());
        }
        Class<? extends Annotation> effectiveScope = scopeOf(bean);
        if (effectiveScope != null) {
            // the effective scope, a stereotype-carried one included: the runtime reads this to know the bean
            // is not dependent
            metadata.addDeclaredAnnotation(CdiScope.class.getName(), Map.of(
                AnnotationMetadata.VALUE_MEMBER, effectiveScope.getName(),
                "normal", effectiveScope.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)));
        }
        if (bean.alternative()) {
            metadata.addDeclaredAnnotation("jakarta.enterprise.inject.Alternative", Map.of());
        }
        return metadata;
    }

    /**
     * The members a qualifier was written with, read off the instance so that the bean's metadata reports
     * {@code @Region("west")} as itself rather than as the annotation's defaults.
     */
    private static Map<CharSequence, Object> membersOf(Annotation annotation) {
        Map<CharSequence, Object> members = new java.util.LinkedHashMap<>();
        for (Method member : annotation.annotationType().getDeclaredMethods()) {
            if (member.getParameterCount() != 0) {
                continue;
            }
            try {
                member.setAccessible(true);
                Object value = member.invoke(annotation);
                if (value != null) {
                    members.put(member.getName(), value);
                }
            } catch (ReflectiveOperationException e) {
                // a member that cannot be read is left to its default
            }
        }
        return members;
    }

    /**
     * Creates a synthetic bean by asking the creator the extension named for it.
     *
     * <p>The creator is instantiated rather than resolved as a bean: the specification has the container
     * instantiate it, and it is named by an extension that may well be compiled apart from the application and
     * have no bean definition of its own. What it needs from the container it asks the lookup it is handed
     * for.</p>
     */
    private <T> T create(SyntheticBean<T> bean,
                         io.micronaut.context.@io.micronaut.core.annotation.Nullable BeanResolutionContext
                             resolutionContext) {
        SyntheticBeanCreator<T> creator;
        try {
            creator = bean.creator().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The creator " + bean.creator().getName() + " of the synthetic bean "
                + bean.implementationClass().getName() + " could not be instantiated: it needs a constructor "
                + "taking no parameters", e);
        }
        io.micronaut.cdi.runtime.CdiInjectionPoint injectionPoint =
            currentInjectionPointOf(bean, resolutionContext);
        if (injectionPoint != null) {
            io.micronaut.cdi.runtime.CurrentInjectionPoint.enter(injectionPoint);
        }
        CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
        boolean handedOver = false;
        try {
            T instance = creator.create(lookup, new CdiParameters(bean.parameters()));
            if (instance != null) {
                // what the creation function looked up as dependent instances belongs to the instance it
                // created, and is destroyed with it (section 2.10.5)
                creatorLookups.put(instance, lookup);
                handedOver = true;
            }
            return instance;
        } finally {
            if (!handedOver) {
                // a creation that failed, or made nothing, leaves nothing to hand the lookups to: what it
                // looked up is let go here rather than leaking
                lookup.destroyTransients();
            }
            if (injectionPoint != null) {
                io.micronaut.cdi.runtime.CurrentInjectionPoint.leave();
            }
        }
    }

    /**
     * The injection point a dependent synthetic bean is being created for, read off the resolution under way:
     * section 2.10.5 hands the creation function a lookup that can answer {@code InjectionPoint}.
     */
    private io.micronaut.cdi.runtime.@io.micronaut.core.annotation.Nullable CdiInjectionPoint currentInjectionPointOf(
        SyntheticBean<?> bean,
        io.micronaut.context.@io.micronaut.core.annotation.Nullable BeanResolutionContext resolutionContext) {
        Class<? extends Annotation> scope = scopeOf(bean);
        if (scope != null && !"jakarta.enterprise.context.Dependent".equals(scope.getName())) {
            // for anything but a dependent bean the specification leaves the answer open, and null it is
            return null;
        }
        if (resolutionContext == null) {
            return null;
        }
        io.micronaut.cdi.runtime.CdiBeanContainer container =
            beanContext.getBean(io.micronaut.cdi.runtime.CdiBeanContainer.class);
        // the resolution the creation function was called for: the path names where this bean is going
        for (io.micronaut.context.BeanResolutionContext.Segment<?, ?> segment : resolutionContext.getPath()) {
            Argument<?> argument = segment.getArgument();
            if (argument != null && argument.getType().isAssignableFrom(bean.implementationClass())
                && !segment.getDeclaringType().getBeanType().equals(bean.implementationClass())) {
                return io.micronaut.cdi.runtime.CdiInjectionPoint.of(
                    container.canonicalBean(segment.getDeclaringType()), segment);
            }
        }
        return null;
    }
}
