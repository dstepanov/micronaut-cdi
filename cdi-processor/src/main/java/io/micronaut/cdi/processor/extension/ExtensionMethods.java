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
package io.micronaut.cdi.processor.extension;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.interceptor.Interceptor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The build compatible extensions of a compilation and the methods of their phases (section 2.10): found as the
 * compilation starts {@link BuildCompatibleExtensionVisitor}, validated, ordered by priority within each phase, and
 * invoked as the visitor reaches each phase. The extension instances are created once and handed every phase, since
 * an extension may keep state from one phase to the next; the lists of methods do not change once loaded.
 *
 * <p>Each phase keeps its own failure policy: a discovery method that fails stops the compilation with an
 * exception; an enhancement method that fails is reported against the compilation; a registration method that
 * fails is too, unless it reported a definition or deployment problem, which travels out as itself; and a synthesis
 * or validation method that fails is reported as a deployment problem and stops its phase.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class ExtensionMethods {

    private static final String EXTENSIONS_OPTION = BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION;

    private final List<ExtensionMethod> discoveries;
    private final List<Enhancer> enhancers;
    private final List<Registrar> registrars;
    private final List<ExtensionMethod> synthesizers;
    private final List<ExtensionMethod> validators;

    private ExtensionMethods(List<ExtensionMethod> discoveries, List<Enhancer> enhancers, List<Registrar> registrars,
                             List<ExtensionMethod> synthesizers, List<ExtensionMethod> validators) {
        this.discoveries = List.copyOf(discoveries);
        this.enhancers = List.copyOf(enhancers);
        this.registrars = List.copyOf(registrars);
        this.synthesizers = List.copyOf(synthesizers);
        this.validators = List.copyOf(validators);
    }

    /**
     * The methods of no extension: what a visitor the compiler never started runs.
     *
     * @return The methods
     */
    static ExtensionMethods none() {
        return new ExtensionMethods(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Finds the extensions of the compilation and the methods of their phases, as the compilation starts this
     * visitor: the ones the compiler options name, and the ones the service loader finds unless the options say
     * the named ones are all of them.
     */
    static ExtensionMethods load(VisitorContext context) {
        Map<String, String> options = context.getOptions();
        List<ExtensionMethod> discoveries = new ArrayList<>();
        List<ExtensionMethod> synthesizers = new ArrayList<>();
        List<ExtensionMethod> validators = new ArrayList<>();
        List<Enhancer> enhancers = new ArrayList<>();
        List<Registrar> registrars = new ArrayList<>();
        List<BuildCompatibleExtension> extensions = new ArrayList<>();
        String named = options.get(EXTENSIONS_OPTION);
        if (named != null) {
            for (String name : named.split(",")) {
                if (!name.isBlank()) {
                    extensions.add(instantiate(name.trim()));
                }
            }
        }
        if (!Boolean.parseBoolean(options.get(BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION))) {
            for (BuildCompatibleExtension loaded : ServiceLoader.load(BuildCompatibleExtension.class,
                BuildCompatibleExtensionVisitor.class.getClassLoader())) {
                // an extension both named and registered as a service runs once
                if (extensions.stream().noneMatch(extension -> extension.getClass().equals(loaded.getClass()))) {
                    extensions.add(loaded);
                }
            }
        }
        // the discovery phase runs once, as the compilation starts and before any class is visited: what it
        // says is about classes by name rather than about the class in front of the compiler, and is applied
        // as those classes come past. Within each phase the methods run by their priority, lowest first
        // (section 2.10)
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : declaredMethodsOf(extension.getClass())) {
                if (method.isAnnotationPresent(Discovery.class)) {
                    validateDiscovery(method);
                    method.setAccessible(true);
                    discoveries.add(new ExtensionMethod(extension, method));
                }
            }
        }
        discoveries.sort(Comparator.comparingInt(ExtensionMethod::priority));
        List<ExtensionMethod> enhancements = new ArrayList<>();
        List<ExtensionMethod> registrations = new ArrayList<>();
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : declaredMethodsOf(extension.getClass())) {
                if (method.isAnnotationPresent(Enhancement.class)) {
                    validateEnhancement(method);
                    method.setAccessible(true);
                    enhancements.add(new ExtensionMethod(extension, method));
                }
                if (method.isAnnotationPresent(Registration.class)) {
                    validateRegistration(method);
                    method.setAccessible(true);
                    registrations.add(new ExtensionMethod(extension, method));
                }
                if (method.isAnnotationPresent(Synthesis.class)) {
                    validateHanded(Phase.SYNTHESIS, method, SyntheticComponents.class);
                    method.setAccessible(true);
                    synthesizers.add(new ExtensionMethod(extension, method));
                }
                if (method.isAnnotationPresent(Validation.class)) {
                    validateHanded(Phase.VALIDATION, method, null);
                    method.setAccessible(true);
                    validators.add(new ExtensionMethod(extension, method));
                }
            }
        }
        enhancements.sort(Comparator.comparingInt(ExtensionMethod::priority));
        registrations.sort(Comparator.comparingInt(ExtensionMethod::priority));
        synthesizers.sort(Comparator.comparingInt(ExtensionMethod::priority));
        validators.sort(Comparator.comparingInt(ExtensionMethod::priority));
        for (ExtensionMethod enhancement : enhancements) {
            enhancers.add(new Enhancer(enhancement.extension(), enhancement.method(),
                enhancement.method().getAnnotation(Enhancement.class)));
        }
        for (ExtensionMethod registration : registrations) {
            registrars.add(new Registrar(registration.extension(), registration.method(),
                registration.method().getAnnotation(Registration.class)));
        }
        return new ExtensionMethods(discoveries, enhancers, registrars, synthesizers, validators);
    }

    /**
     * Runs the discovery phase (section 2.10.1): each discovery method, by its priority.
     *
     * @param discovered What the discovery phase says, which the methods are handed
     * @param context    The compilation
     */
    void discover(DiscoveredClasses discovered, VisitorContext context) {
        for (ExtensionMethod discovery : discoveries) {
            discover(discovery.extension(), discovery.method(), discovered, context);
        }
    }

    /**
     * Whether any extension has a registration method (section 2.10.3).
     *
     * @return Whether one has
     */
    boolean registers() {
        return !registrars.isEmpty();
    }

    /**
     * Whether any extension has a method of the phases that follow the registration of the compiled beans: a
     * registration method, which synthetic beans are described to, a synthesis or a validation method.
     *
     * @return Whether one has
     */
    boolean hasLatePhases() {
        return !synthesizers.isEmpty() || !validators.isEmpty() || !registrars.isEmpty();
    }

    /**
     * Describes a bean of the compilation to each registration method that asked about it.
     *
     * @param bean     The bean
     * @param messages What the methods report through
     * @param context  The compilation
     */
    void describeBean(ElementBeanInfo bean, Messages messages, VisitorContext context) {
        for (Registrar registrar : registrars) {
            if (registrar.matches(bean)) {
                registrar.describe(bean, messages, context);
            }
        }
    }

    /**
     * Describes an observer method of the compilation to each registration method that asked about its event type.
     *
     * @param observer The observer
     * @param messages What the methods report through
     * @param context  The compilation
     */
    void describeObserver(ElementObserverInfo observer, Messages messages, VisitorContext context) {
        for (Registrar registrar : registrars) {
            if (registrar.matchesObserver(observer)) {
                registrar.describeObserver(observer, messages, context);
            }
        }
    }

    /**
     * Describes a synthetic bean to each registration method that asks about beans of a type it has.
     *
     * @param bean     The bean
     * @param beanType Its bean type
     * @param messages What the methods report through
     * @param context  The compilation
     */
    void describeSyntheticBean(SyntheticBeanInfo bean, ClassElement beanType, Messages messages,
                               VisitorContext context) {
        for (Registrar registrar : registrars) {
            if (registrar.asksForBeans(false) && registrar.matches(beanType)) {
                registrar.describe(bean, messages, context);
            }
        }
    }

    /**
     * Describes a synthetic observer to each registration method that asks about observers of its event type.
     *
     * @param observer  The observer
     * @param eventType Its observed event type
     * @param messages  What the methods report through
     * @param context   The compilation
     */
    void describeSyntheticObserver(SyntheticObserverInfo observer, ClassElement eventType, Messages messages,
                                   VisitorContext context) {
        for (Registrar registrar : registrars) {
            if (registrar.asksForObservers() && registrar.matches(eventType)) {
                registrar.describeObserver(observer, messages, context);
            }
        }
    }

    /**
     * Runs the synthesis phase (section 2.10.5): each synthesis method, by its priority, handed the components
     * of its own extension.
     *
     * @param synthesis The phase, which collects what the methods describe
     * @param messages  What the methods report through
     * @param context   The compilation
     * @return Whether every method ran; a failure fails the compilation and stops the phase
     */
    boolean synthesize(SynthesisPhase synthesis, Messages messages, VisitorContext context) {
        for (ExtensionMethod synthesizer : synthesizers) {
            Class<?>[] parameterTypes = synthesizer.method().getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = parameterTypes[i].equals(SyntheticComponents.class)
                    ? synthesis.componentsOf(synthesizer.extension())
                    : Phase.SYNTHESIS.argument(parameterTypes[i], messages, context);
            }
            if (!invokeLatePhase("synthesis", synthesizer, arguments, context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs the validation phase (section 2.10.6): each validation method, by its priority.
     *
     * @param messages What the methods report through
     * @param context  The compilation
     */
    void validate(Messages messages, VisitorContext context) {
        for (ExtensionMethod validator : validators) {
            Class<?>[] parameterTypes = validator.method().getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = Phase.VALIDATION.argument(parameterTypes[i], messages, context);
            }
            if (!invokeLatePhase("validation", validator, arguments, context)) {
                return;
            }
        }
    }

    /**
     * Runs the enhancement methods that asked for the class on it (section 2.10.2), by their priority.
     *
     * @param element The class
     * @param context The compilation
     */
    void enhance(ClassElement element, VisitorContext context) {
        if (enhancers.isEmpty()) {
            return;
        }
        Messages messages = new VisitorMessages(context);
        for (Enhancer enhancer : enhancers) {
            if (enhancer.matches(element)) {
                enhancer.enhance(element, messages, context);
            }
        }
    }

    /**
     * Creates the extension a compiler option named, through the loader of this module, which the processor
     * path's classes are visible to, or else through the loader of the thread the compilation runs on, which is
     * what a compilation run inside a test sees its classes through.
     */
    private static BuildCompatibleExtension instantiate(String name) {
        Class<?> type;
        try {
            type = Class.forName(name, true, BuildCompatibleExtensionVisitor.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                type = Class.forName(name, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException | RuntimeException other) {
                throw new IllegalStateException("The build compatible extension " + name + ", named by the option "
                    + EXTENSIONS_OPTION + ", is not on the classpath of the compilation", e);
            }
        }
        if (!BuildCompatibleExtension.class.isAssignableFrom(type)) {
            throw new IllegalStateException("The class " + name + ", named by the option " + EXTENSIONS_OPTION
                + ", is not a " + BuildCompatibleExtension.class.getName());
        }
        try {
            return (BuildCompatibleExtension) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The build compatible extension " + name + ", named by the option "
                + EXTENSIONS_OPTION + ", could not be created through a constructor without parameters", e);
        }
    }

    private static void validateDiscovery(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!parameterType.equals(ScannedClasses.class) && !parameterType.equals(MetaAnnotations.class)
                && !Phase.DISCOVERY.hands(parameterType)) {
                throw Phase.DISCOVERY.unsupported(method, parameterType);
            }
        }
    }

    private static void validateEnhancement(Method method) {
        int queried = 0;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType.equals(ClassConfig.class) || parameterType.equals(MethodConfig.class)
                || parameterType.equals(FieldConfig.class)
                || parameterType.equals(ClassInfo.class)
                || parameterType.equals(MethodInfo.class)
                || parameterType.equals(FieldInfo.class)) {
                queried++;
            } else if (!Phase.ENHANCEMENT.hands(parameterType)) {
                throw Phase.ENHANCEMENT.unsupported(method, parameterType);
            }
        }
        if (queried != 1) {
            throw new DefinitionException("The @Enhancement method " + method
                + " must declare exactly one parameter naming what it enhances (section 2.10.2)");
        }
    }

    private static void validateRegistration(Method method) {
        int queried = 0;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType.equals(BeanInfo.class)
                || parameterType.equals(InterceptorInfo.class)
                || parameterType.equals(ObserverInfo.class)) {
                queried++;
            } else if (!Phase.REGISTRATION.hands(parameterType)) {
                throw Phase.REGISTRATION.unsupported(method, parameterType);
            }
        }
        if (queried != 1) {
            throw new DefinitionException("The @Registration method " + method
                + " must declare exactly one parameter naming what it is told about (section 2.10.3)");
        }
    }

    private static void validateHanded(Phase phase, Method method,
                                       @Nullable Class<?> subject) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!parameterType.equals(subject) && !phase.hands(parameterType)) {
                throw phase.unsupported(method, parameterType);
            }
        }
    }

    /**
     * Runs one discovery method, handing it what it asked for.
     */
    private static void discover(BuildCompatibleExtension extension, Method method, DiscoveredClasses discovered,
                                 VisitorContext context) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(ScannedClasses.class) || parameterTypes[i].equals(MetaAnnotations.class)) {
                arguments[i] = discovered;
            } else if (Phase.DISCOVERY.hands(parameterTypes[i])) {
                arguments[i] = Phase.DISCOVERY.argument(parameterTypes[i], new VisitorMessages(context), context);
            } else {
                throw new IllegalStateException("The discovery method " + method + " asks for a "
                    + parameterTypes[i].getName() + ", which this module does not hand to one");
            }
        }
        try {
            method.invoke(extension, arguments);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("The discovery method " + method + " failed", e);
        }
    }

    /**
     * Invokes a synthesis or validation method. A problem it throws is a problem with the deployment, and
     * fails the compilation that stands for deploying it.
     */
    private static boolean invokeLatePhase(String phase, ExtensionMethod extensionMethod, Object[] arguments,
                                           VisitorContext context) {
        Method method = extensionMethod.method();
        try {
            method.invoke(extensionMethod.extension(), arguments);
            return true;
        } catch (IllegalAccessException e) {
            context.fail(VisitorMessages.DEPLOYMENT_PROBLEM + "the " + phase + " method " + method
                + " could not be invoked: " + e, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof DefinitionException) {
                context.fail("The " + phase + " method " + method + " failed: " + cause, null);
            } else {
                context.fail(VisitorMessages.DEPLOYMENT_PROBLEM + "the " + phase + " method " + method
                    + " failed: " + cause, null);
            }
        }
        return false;
    }

    /**
     * One registration method of one extension, and the beans it asked to be told about.
     *
     * @param extension    The extension that declares the method
     * @param method       The registration method
     * @param registration What it asked to be told about
     */
    private record Registrar(BuildCompatibleExtension extension, Method method, Registration registration) {

        /**
         * Whether the method asks to be told about the bean: a method that asks for a bean asks about any, one
         * that asks for an interceptor about an interceptor alone, and either about the ones that have a type it
         * names among their bean types.
         */
        private boolean matches(ElementBeanInfo bean) {
            if (!asksForBeans(false) && !(bean.isInterceptor() && asksForBeans(true))) {
                return false;
            }
            for (Class<?> asked : registration.types()) {
                if (bean.hasTypeOfClass(asked.getName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the method asks to be told about beans: about any bean, or - where interceptors count -
         * about interceptors.
         */
        private boolean asksForBeans(boolean orInterceptors) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(BeanInfo.class) || (orInterceptors && parameterType.equals(
                    InterceptorInfo.class))) {
                    return true;
                }
            }
            return false;
        }

        private boolean asksForObservers() {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(ObserverInfo.class)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the type is one the method asked to be told about.
         */
        private boolean matches(ClassElement type) {
            for (Class<?> asked : registration.types()) {
                if (type.isAssignable(asked)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesObserver(ElementObserverInfo observer) {
            return asksForObservers() && matches(observer.observedType());
        }

        private void describeObserver(ObserverInfo observer,
                                      Messages messages, VisitorContext context) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(ObserverInfo.class)) {
                    arguments[i] = observer;
                } else if (Phase.REGISTRATION.hands(parameterTypes[i])) {
                    arguments[i] = Phase.REGISTRATION.argument(parameterTypes[i], messages, context);
                } else {
                    context.fail("The registration method " + method + " asks for a "
                        + parameterTypes[i].getName() + ", which this module does not hand to one", null);
                    return;
                }
            }
            try {
                method.invoke(extension, arguments);
            } catch (IllegalAccessException e) {
                context.fail("The registration method " + method + " could not be invoked: " + e, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                rethrowDeploymentProblem(cause);
                context.fail("The registration method " + method + " failed: " + cause, null);
            }
        }

        private void describe(BeanInfo bean, Messages messages, VisitorContext context) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(BeanInfo.class)
                    || (parameterTypes[i].equals(
                        InterceptorInfo.class)
                        && bean instanceof InterceptorInfo)) {
                    arguments[i] = bean;
                } else if (Phase.REGISTRATION.hands(parameterTypes[i])) {
                    arguments[i] = Phase.REGISTRATION.argument(parameterTypes[i], messages, context);
                } else {
                    context.fail("The registration method " + method + " asks for a "
                        + parameterTypes[i].getName() + ", which this module does not hand to one", null);
                    return;
                }
            }
            try {
                method.invoke(extension, arguments);
            } catch (IllegalAccessException e) {
                context.fail("The registration method " + method + " could not be invoked: " + e, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                rethrowDeploymentProblem(cause);
                context.fail("The registration method " + method + " failed: " + cause, null);
            }
        }

        /**
         * Lets a definition or deployment problem the extension reported travel out as itself, so that what
         * stops the deployment is the exception the specification names rather than a compile diagnostic.
         */
        private static void rethrowDeploymentProblem(Throwable cause) {
            if (cause instanceof DeploymentException deployment) {
                throw deployment;
            }
            if (cause instanceof DefinitionException definition) {
                throw definition;
            }
        }
    }

    /**
     * One enhancement method of one extension, and the classes it asked to enhance.
     *
     * @param extension   The extension that declares the method
     * @param method      The enhancement method
     * @param enhancement What it asked to enhance
     */
    private record Enhancer(BuildCompatibleExtension extension, Method method, Enhancement enhancement) {

        private boolean matches(ClassElement element) {
            boolean ofTheRightType = false;
            for (Class<?> type : enhancement.types()) {
                if (enhancement.withSubtypes() ? element.isAssignable(type) : element.getName().equals(type.getName())) {
                    ofTheRightType = true;
                    break;
                }
            }
            if (!ofTheRightType) {
                return false;
            }
            Class<? extends Annotation>[] required = enhancement.withAnnotations();
            if (required.length == 0) {
                return true;
            }
            for (Class<? extends Annotation> annotation : required) {
                if (carries(element, annotation.getName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the class carries the annotation the enhancement asked for, anywhere the specification says
         * to look: on the type, on any member, on any parameter of any member, or as a meta-annotation of any
         * of those. {@code java.lang.annotation.Annotation} asks for types that use any annotation at all.
         */
        private static boolean carries(ClassElement element, String annotation) {
            boolean any = "java.lang.annotation.Annotation".equals(annotation);
            // the class probe reads what the class itself declares: hasStereotype would also match an
            // annotation Micronaut merged down from a supertype, which the specification does not ask for
            if (any
                ? !element.getDeclaredAnnotationNames().isEmpty()
                : element.hasDeclaredAnnotation(annotation)
                    || carriedAsMetaAnnotation(element.getAnnotationMetadata(), annotation)) {
                return true;
            }
            for (MethodElement method
                : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
                if (memberCarries(method, annotation, any)) {
                    return true;
                }
            }
            for (ConstructorElement constructor
                : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
                if (memberCarries(constructor, annotation, any)) {
                    return true;
                }
            }
            for (FieldElement field
                : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
                if (any
                    ? !field.getDeclaredAnnotationNames().isEmpty()
                    : field.hasDeclaredAnnotation(annotation)
                        || carriedAsMetaAnnotation(field.getAnnotationMetadata(), annotation)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean memberCarries(MethodElement method, String annotation,
                                             boolean any) {
            if (any
                ? !method.getDeclaredAnnotationNames().isEmpty()
                : method.hasDeclaredAnnotation(annotation)
                    || carriedAsMetaAnnotation(method.getAnnotationMetadata(), annotation)) {
                return true;
            }
            for (ParameterElement parameter : method.getParameters()) {
                if (any
                    ? !parameter.getDeclaredAnnotationNames().isEmpty()
                    : parameter.hasDeclaredAnnotation(annotation)
                        || carriedAsMetaAnnotation(parameter.getAnnotationMetadata(), annotation)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether an annotation the element declares is itself annotated with the one asked for, which the
         * specification counts as the annotation being used.
         */
        private static boolean carriedAsMetaAnnotation(AnnotationMetadata metadata,
                                                       String annotation) {
            // what carries the asked-for annotation as a meta-annotation: the query answers the annotations
            // that lead to it, and any of them being declared here is what counts
            List<String> carriers = metadata.getAnnotationNamesByStereotype(annotation);
            return metadata.getDeclaredAnnotationNames().stream().anyMatch(carriers::contains);
        }

        private void enhance(ClassElement element, Messages messages, VisitorContext context) {
            ElementClassConfig classConfig = new ElementClassConfig(element, context);
            Class<?>[] parameterTypes = method.getParameterTypes();
            // a method taking a configuration of a member is invoked once for each of those members, and one
            // taking a configuration of the class once for the class
            List<Object[]> invocations = new ArrayList<>();
            if (takes(parameterTypes, MethodConfig.class)
                || takes(parameterTypes, MethodInfo.class)) {
                classConfig.methods().forEach(m -> invocations.add(arguments(parameterTypes, m, messages, context)));
            } else if (takes(parameterTypes, FieldConfig.class)
                || takes(parameterTypes, FieldInfo.class)) {
                classConfig.fields().forEach(f -> invocations.add(arguments(parameterTypes, f, messages, context)));
            } else {
                invocations.add(arguments(parameterTypes, classConfig, messages, context));
            }
            for (Object[] arguments : invocations) {
                invoke(arguments, context);
            }
        }

        private void invoke(Object[] arguments, VisitorContext context) {
            try {
                method.invoke(extension, arguments);
            } catch (IllegalAccessException e) {
                context.fail("The enhancement method " + method + " could not be invoked: " + e, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                context.fail("The enhancement method " + method + " failed: " + cause, null);
            }
        }

        private static boolean takes(Class<?>[] parameterTypes, Class<?> type) {
            for (Class<?> parameterType : parameterTypes) {
                if (parameterType.equals(type)) {
                    return true;
                }
            }
            return false;
        }

        private static Object[] arguments(Class<?>[] parameterTypes, Object config, Messages messages,
                                          VisitorContext context) {
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = Phase.ENHANCEMENT.hands(parameterTypes[i])
                    ? Phase.ENHANCEMENT.argument(parameterTypes[i], messages, context)
                    : readOnlyOrConfig(parameterTypes[i], config);
            }
            return arguments;
        }

        /**
         * What an enhancement parameter is handed: the configuration, or — for a method that only reads — the
         * declaration the configuration is of.
         */
        private static Object readOnlyOrConfig(Class<?> parameterType, Object config) {
            if (parameterType.equals(MethodInfo.class)
                && config instanceof MethodConfig methodConfig) {
                return methodConfig.info();
            }
            if (parameterType.equals(FieldInfo.class)
                && config instanceof FieldConfig fieldConfig) {
                return fieldConfig.info();
            }
            if (parameterType.equals(ClassInfo.class)
                && config instanceof ClassConfig cc) {
                return cc.info();
            }
            return config;
        }
    }

    /**
     * What each phase hands an extension method besides what the method is about (sections 2.10.1 to 2.10.3):
     * the one list that both the validation of a method and its invocation read.
     */
    private enum Phase {
        DISCOVERY("@Discovery", "2.10.1", Messages.class),
        ENHANCEMENT("@Enhancement", "2.10.2", Messages.class,
            Types.class),
        REGISTRATION("@Registration", "2.10.3", Messages.class,
            Types.class,
            InvokerFactory.class),
        SYNTHESIS("@Synthesis", "2.10.4", Messages.class,
            Types.class),
        VALIDATION("@Validation", "2.10.5", Messages.class,
            Types.class);

        private final String annotation;
        private final String section;
        private final List<Class<?>> handed;

        Phase(String annotation, String section, Class<?>... handed) {
            this.annotation = annotation;
            this.section = section;
            this.handed = List.of(handed);
        }

        /**
         * Whether the phase hands a method a parameter of the type.
         */
        boolean hands(Class<?> parameterType) {
            return handed.contains(parameterType);
        }

        /**
         * What a parameter of a type the phase hands is given.
         */
        Object argument(Class<?> parameterType, Messages messages,
                        @Nullable VisitorContext context) {
            if (parameterType.equals(Messages.class)) {
                return messages;
            }
            if (context != null && hands(parameterType)) {
                if (parameterType.equals(Types.class)) {
                    return new VisitorTypes(context);
                }
                if (parameterType.equals(InvokerFactory.class)) {
                    return new ElementInvokerFactory();
                }
            }
            throw new IllegalStateException("The " + annotation + " phase does not hand a "
                + parameterType.getName());
        }

        DefinitionException unsupported(Method method, Class<?> parameterType) {
            return new DefinitionException("The " + annotation + " method " + method
                + " declares a parameter of type " + parameterType.getName()
                + ", which the phase does not hand to one (section " + section + ")");
        }
    }

    /**
     * One method of one extension, and the priority it runs at within its phase.
     *
     * @param extension The extension
     * @param method    The method
     */
    /**
     * The methods a class declares, in an order every compilation sees alike: by name, then by parameter types. The
     * order reflection hands them out in is unspecified, and the synthetic components of an extension are
     * identified by the order its synthesis methods described them in, so two compilations of one application
     * running the methods in different orders would record one component under two identities, and two components
     * under one.
     */
    private static List<Method> declaredMethodsOf(Class<?> type) {
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
        methods.sort(Comparator.comparing(Method::getName)
            .thenComparing(method -> Arrays.toString(method.getParameterTypes())));
        return methods;
    }

    private record ExtensionMethod(BuildCompatibleExtension extension, Method method) {

        private int priority() {
            Priority priority = method.getAnnotation(Priority.class);
            // the default of section 2.10: halfway through the application range
            return priority != null ? priority.value()
                : Interceptor.Priority.APPLICATION + 500;
        }
    }
}
