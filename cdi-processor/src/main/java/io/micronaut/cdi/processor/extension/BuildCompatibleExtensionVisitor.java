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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Runs the enhancement phase of the build compatible extensions of section 2.10, while the classes they enhance
 * are being compiled.
 *
 * <p>An extension of that kind is written to run at build time, which is where this container does its work
 * anyway, so the phase is not something that has to be arranged for: it is a visitor like any other, and what an
 * extension changes about a class is changed before Micronaut generates the bean definition for it.</p>
 *
 * <p>The extensions themselves are found the way the specification says, through the service loader — from the
 * annotation processor classpath, since that is the classpath of the build rather than of the application. An
 * extension therefore goes on the annotation processor path beside this module.</p>
 *
 * <p>An enhancement method is invoked once for each of the declarations it asked for: once per matching class for
 * a method taking a {@link ClassConfig}, once per method of each matching class for one taking a
 * {@link MethodConfig}, and once per field for a {@link FieldConfig}. A {@link Messages} parameter is handed the
 * compiler to report through.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BuildCompatibleExtensionVisitor implements TypeElementVisitor<Object, Object> {

    private static volatile @io.micronaut.core.annotation.Nullable List<BuildCompatibleExtension> overriddenExtensions;
    private static volatile @io.micronaut.core.annotation.Nullable BuildCompatibleExtensionVisitor current;
    private static volatile io.micronaut.inject.visitor.@io.micronaut.core.annotation.Nullable VisitorContext
        activeContext;

    private final List<Enhancer> enhancers = new ArrayList<>();
    private final List<Registrar> registrars = new ArrayList<>();
    private final DiscoveredClasses discovered = new DiscoveredClasses();
    private boolean scannedImportWritten;
    private boolean contextRecordWritten;

    public BuildCompatibleExtensionVisitor() {
        current = this;
        List<BuildCompatibleExtension> extensions = new ArrayList<>();
        List<BuildCompatibleExtension> overridden = overriddenExtensions;
        if (overridden != null) {
            extensions.addAll(overridden);
        } else {
            ServiceLoader.load(BuildCompatibleExtension.class, BuildCompatibleExtensionVisitor.class.getClassLoader())
                .forEach(extensions::add);
        }
        // the discovery phase runs once, before anything is compiled: what it says is about classes by name
        // rather than about the class in front of the compiler, and is applied as those classes come past.
        // Within each phase the methods run by their priority, lowest first (section 2.10)
        List<ExtensionMethod> discoveries = new ArrayList<>();
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : extension.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Discovery.class)) {
                    validateDiscovery(method);
                    method.setAccessible(true);
                    discoveries.add(new ExtensionMethod(extension, method));
                }
            }
        }
        discoveries.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        for (ExtensionMethod discovery : discoveries) {
            discover(discovery.extension(), discovery.method());
        }
        List<ExtensionMethod> enhancements = new ArrayList<>();
        List<ExtensionMethod> registrations = new ArrayList<>();
        for (BuildCompatibleExtension extension : extensions) {
            for (Method method : extension.getClass().getDeclaredMethods()) {
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
            }
        }
        enhancements.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        registrations.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        for (ExtensionMethod enhancement : enhancements) {
            enhancers.add(new Enhancer(enhancement.extension(), enhancement.method(),
                enhancement.method().getAnnotation(Enhancement.class)));
        }
        for (ExtensionMethod registration : registrations) {
            registrars.add(new Registrar(registration.extension(), registration.method(),
                registration.method().getAnnotation(Registration.class)));
        }
    }

    /**
     * Hands this visitor the extensions of one deployment, in place of the service loading it does on its own:
     * what a harness that compiles a deployment at a time — the kit's — sets around each compilation. Setting
     * {@code null} puts the service loading back.
     *
     * @param extensions The extensions, or {@code null} to load them as usual
     */
    public static void overrideExtensions(@io.micronaut.core.annotation.Nullable List<BuildCompatibleExtension> extensions) {
        overriddenExtensions = extensions;
        RemovedAnnotations.reset();
    }

    /**
     * The visitor of the compilation under way, so that the registration visitor — which runs last, after
     * every other visitor has had its say — describes the beans with the same extensions and state.
     *
     * @return The visitor, or {@code null} outside a compilation
     */
    static @io.micronaut.core.annotation.Nullable BuildCompatibleExtensionVisitor current() {
        return current;
    }

    /**
     * The visitor context of the compilation under way, for the model classes that need the compiler's view of
     * a class the element at hand does not name — the implicit {@code java.lang.Object} superclass, say.
     *
     * @return The context, or {@code null} outside a compilation
     */
    static io.micronaut.inject.visitor.@io.micronaut.core.annotation.Nullable VisitorContext activeVisitorContext() {
        return activeContext;
    }

    /**
     * The classes the discovery phase of the compilation under way added to the scanned ones, for the harness
     * that compiles a deployment at a time: an archive without a beans.xml has no discovered beans beyond
     * these.
     *
     * @return The class names
     */
    public static java.util.Set<String> lastScannedClasses() {
        BuildCompatibleExtensionVisitor visitor = current;
        return visitor == null ? java.util.Set.of()
            : java.util.Set.copyOf(visitor.discovered.scannedClasses());
    }

    private static void validateDiscovery(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!parameterType.equals(ScannedClasses.class) && !parameterType.equals(MetaAnnotations.class)
                && !parameterType.equals(Messages.class)) {
                throw new jakarta.enterprise.inject.spi.DefinitionException("The @Discovery method " + method
                    + " declares a parameter of type " + parameterType.getName()
                    + ", which the phase does not hand to one (section 2.10.1)");
            }
        }
    }

    private static void validateEnhancement(Method method) {
        int queried = 0;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType.equals(ClassConfig.class) || parameterType.equals(MethodConfig.class)
                || parameterType.equals(FieldConfig.class)
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.ClassInfo.class)
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.MethodInfo.class)
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.FieldInfo.class)) {
                queried++;
            } else if (!parameterType.equals(Messages.class)
                && !parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                throw new jakarta.enterprise.inject.spi.DefinitionException("The @Enhancement method " + method
                    + " declares a parameter of type " + parameterType.getName()
                    + ", which the phase does not hand to one (section 2.10.2)");
            }
        }
        if (queried != 1) {
            throw new jakarta.enterprise.inject.spi.DefinitionException("The @Enhancement method " + method
                + " must declare exactly one parameter naming what it enhances (section 2.10.2)");
        }
    }

    private static void validateRegistration(Method method) {
        int queried = 0;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.BeanInfo.class)
                || parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo.class)
                || parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
                queried++;
            } else if (!parameterType.equals(Messages.class)
                && !parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)
                && !parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.InvokerFactory.class)) {
                throw new jakarta.enterprise.inject.spi.DefinitionException("The @Registration method " + method
                    + " declares a parameter of type " + parameterType.getName()
                    + ", which the phase does not hand to one (section 2.10.3)");
            }
        }
        if (queried != 1) {
            throw new jakarta.enterprise.inject.spi.DefinitionException("The @Registration method " + method
                + " must declare exactly one parameter naming what it is told about (section 2.10.3)");
        }
    }

    /**
     * Runs one discovery method, handing it what it asked for.
     */
    private void discover(BuildCompatibleExtension extension, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(ScannedClasses.class) || parameterTypes[i].equals(MetaAnnotations.class)) {
                arguments[i] = discovered;
            } else if (parameterTypes[i].equals(Messages.class)) {
                arguments[i] = null;
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

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * An enhancement decides what a class says about itself, so it runs before everything else here.
     *
     * <p>Micronaut runs its visitors from the highest order down, so the one that runs first is the one that
     * reports the lowest precedence.</p>
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        // before every other visitor there is — the interceptor machinery included: what an enhancement adds
        // to a class has to be there when anything else reads it. The visitors run highest order first
        return LOWEST_PRECEDENCE;
    }

    private void writeContextRecord(VisitorContext context) {
        if (contextRecordWritten
            || (discovered.contextRecords().isEmpty() && discovered.registeredQualifiers().isEmpty())) {
            return;
        }
        contextRecordWritten = true;
        StringBuilder source = new StringBuilder("package io.micronaut.cdi.generated;\n\n"
            + "@jakarta.inject.Singleton\n");
        if (!discovered.contextRecords().isEmpty()) {
            source.append("@io.micronaut.cdi.annotation.CdiExtensionContextRecord({\n");
            for (String record : discovered.contextRecords()) {
                source.append("    \"").append(record).append("\",\n");
            }
            source.append("})\n");
        }
        if (!discovered.registeredQualifiers().isEmpty()) {
            source.append("@io.micronaut.cdi.annotation.CdiExtensionQualifiers({\n");
            for (String qualifier : discovered.registeredQualifiers()) {
                source.append("    \"").append(qualifier).append("\",\n");
            }
            source.append("})\n");
        }
        source.append("final class ExtensionContextRecordHolder {\n}\n");
        context.visitGeneratedSourceFile("io.micronaut.cdi.generated", "ExtensionContextRecordHolder")
            .ifPresent(file -> {
                try {
                    file.write(writer -> writer.write(source.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException("The extension context record could not be written", e);
                }
            });
    }

    private void writeScannedImport(VisitorContext context) {
        writeContextRecord(context);
        if (scannedImportWritten || discovered.scannedClasses().isEmpty()) {
            return;
        }
        scannedImportWritten = true;
        // a class the discovery phase added to the scanned ones may say nothing at all on its own, and a class
        // with nothing on it is never handed to the bean machinery: a generated import names them all, and
        // its processing is what makes each a bean (their scope was put on as they were visited)
        StringBuilder source = new StringBuilder("package io.micronaut.cdi.generated;\n\n"
            + "@io.micronaut.context.annotation.ClassImport(classes = {\n");
        for (String scannedClass : discovered.scannedClasses()) {
            source.append("    ").append(scannedClass).append(".class,\n");
        }
        source.append("})\nfinal class ScannedClassesImport {\n}\n");
        context.visitGeneratedSourceFile("io.micronaut.cdi.generated", "ScannedClassesImport")
            .ifPresent(file -> {
                try {
                    file.write(writer -> writer.write(source.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException("The scanned classes import could not be written", e);
                }
            });
    }

    @Override
    public void start(VisitorContext context) {
        // what the discovery phase said about annotations is put on the annotation types before any class is
        // visited: a class's metadata folds its annotations' metadata in as it is built, and a qualifier or
        // binding registered by an extension has to be one by then
        for (String describedClass : discovered.describedClassNames()) {
            context.getClassElement(describedClass).ifPresent(this::applyWhatWasDiscovered);
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        activeContext = context;
        // written as the first class is visited, so that the compiler still has rounds ahead of it to process
        // the generated import in
        writeScannedImport(context);
        applyWhatWasDiscovered(element);
        if (enhancers.isEmpty() && registrars.isEmpty()) {
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
     * Describes to the extensions each bean this class declares: the class itself where it is a bean, and every
     * producer it declares.
     *
     * <p>The phase is invoked once per bean rather than once for the container, which is what the specification
     * says it is and what a compiler can give. It runs last, so that what it describes is the bean as everything
     * else has left it.</p>
     */
    void register(ClassElement element, Messages messages, VisitorContext context) {
        activeContext = context;
        if (registrars.isEmpty()) {
            return;
        }
        List<ElementBeanInfo> beans = new ArrayList<>();
        if (element.hasStereotype(CdiScope.class)
            || element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor")) {
            beans.add(new ElementBeanInfo(element, null));
        }
        element.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
            .filter(method -> method.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(method -> beans.add(new ElementBeanInfo(element, method)));
        element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .filter(field -> field.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(field -> beans.add(new ElementBeanInfo(element, field)));
        for (ElementBeanInfo bean : beans) {
            for (Registrar registrar : registrars) {
                if (registrar.matches(bean)) {
                    registrar.describe(bean, messages, context);
                }
            }
        }
        // section 2.10.3 also tells the phase about the observers: a registration method asking for an
        // ObserverInfo is invoked once for each observer whose observed event type matches
        for (io.micronaut.inject.ast.MethodElement method
            : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            io.micronaut.inject.ast.ParameterElement observed = null;
            boolean async = false;
            for (io.micronaut.inject.ast.ParameterElement parameter : method.getParameters()) {
                if (parameter.hasDeclaredAnnotation("jakarta.enterprise.event.Observes")) {
                    observed = parameter;
                } else if (parameter.hasDeclaredAnnotation("jakarta.enterprise.event.ObservesAsync")) {
                    observed = parameter;
                    async = true;
                }
            }
            if (observed == null) {
                continue;
            }
            ElementObserverInfo observer = new ElementObserverInfo(element, method, observed, async);
            for (Registrar registrar : registrars) {
                if (registrar.matchesObserver(observer)) {
                    registrar.describeObserver(observer, messages, context);
                }
            }
        }
    }

    /**
     * Puts on the class what the discovery phase said about it: the annotation that makes it a qualifier, an
     * interceptor binding or a stereotype, and the scope that makes it a bean where it was added to the
     * scanned classes.
     */
    private void applyWhatWasDiscovered(ClassElement element) {
        if (discovered.isEmpty()) {
            return;
        }
        if (discovered.isScanned(element.getName())
            && !element.getAnnotationMetadata().hasStereotype("jakarta.inject.Scope")
            && !element.getAnnotationMetadata().hasStereotype("io.micronaut.cdi.annotation.CdiScope")) {
            // added to the scanned classes during discovery: a bean as though it declared the dependent
            // scope. Only the prototype pseudo-scope is written — a scope a stereotype gives the class must
            // win, and a bean with nothing else reports the dependent scope anyway
            element.annotate("io.micronaut.context.annotation.Prototype");
        }
        for (AnnotationValue<?> annotation : discovered.annotationsFor(element.getName())) {
            element.annotate(annotation);
        }
        java.util.Map<String, List<AnnotationValue<?>>> members =
            discovered.memberAnnotationsFor(element.getName());
        if (!members.isEmpty()) {
            for (io.micronaut.inject.ast.MethodElement method
                : element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS)) {
                for (AnnotationValue<?> annotation : members.getOrDefault(method.getName(), List.of())) {
                    method.annotate(annotation);
                }
            }
        }
    }

    /**
     * One registration method of one extension, and the beans it asked to be told about.
     *
     * @param extension    The extension that declares the method
     * @param method       The registration method
     * @param registration What it asked to be told about
     */
    private record Registrar(BuildCompatibleExtension extension, Method method, Registration registration) {

        private boolean matches(ElementBeanInfo bean) {
            boolean asksForBeans = false;
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(BeanInfo.class)
                    || parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo.class)) {
                    asksForBeans = true;
                }
            }
            if (!asksForBeans) {
                return false;
            }
            for (Class<?> type : registration.types()) {
                if (bean.beanType().isAssignable(type)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesObserver(ElementObserverInfo observer) {
            boolean asksForObservers = false;
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
                    asksForObservers = true;
                }
            }
            if (!asksForObservers) {
                return false;
            }
            for (Class<?> type : registration.types()) {
                if (observer.observedParameter().getGenericType().isAssignable(type)) {
                    return true;
                }
            }
            return false;
        }

        private void describeObserver(ElementObserverInfo observer, Messages messages, VisitorContext context) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(Messages.class)) {
                    arguments[i] = messages;
                } else if (parameterTypes[i].equals(
                    jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
                    arguments[i] = observer;
                } else if (parameterTypes[i].equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                    arguments[i] = new VisitorTypes(context);
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

        private void describe(ElementBeanInfo bean, Messages messages, VisitorContext context) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(Messages.class)) {
                    arguments[i] = messages;
                } else if (parameterTypes[i].equals(BeanInfo.class)
                    || parameterTypes[i].equals(
                        jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo.class)) {
                    arguments[i] = bean;
                } else if (parameterTypes[i].equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                    arguments[i] = new VisitorTypes(context);
                } else if (parameterTypes[i].equals(
                    jakarta.enterprise.inject.build.compatible.spi.InvokerFactory.class)) {
                    arguments[i] = new ElementInvokerFactory();
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
            if (cause instanceof jakarta.enterprise.inject.spi.DeploymentException deployment) {
                throw deployment;
            }
            if (cause instanceof jakarta.enterprise.inject.spi.DefinitionException definition) {
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
         * Whether the class, or one of the members it declares, carries the annotation the enhancement asked for.
         */
        private static boolean carries(ClassElement element, String annotation) {
            if (element.hasDeclaredAnnotation(annotation)) {
                return true;
            }
            return element.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
                .anyMatch(method -> method.hasDeclaredAnnotation(annotation))
                || element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
                .anyMatch(field -> field.hasDeclaredAnnotation(annotation));
        }

        private void enhance(ClassElement element, Messages messages, VisitorContext context) {
            ElementClassConfig classConfig = new ElementClassConfig(element);
            Class<?>[] parameterTypes = method.getParameterTypes();
            // a method taking a configuration of a member is invoked once for each of those members, and one
            // taking a configuration of the class once for the class
            List<Object[]> invocations = new ArrayList<>();
            if (takes(parameterTypes, MethodConfig.class)
                || takes(parameterTypes, jakarta.enterprise.lang.model.declarations.MethodInfo.class)) {
                classConfig.methods().forEach(m -> invocations.add(arguments(parameterTypes, m, messages)));
            } else if (takes(parameterTypes, FieldConfig.class)
                || takes(parameterTypes, jakarta.enterprise.lang.model.declarations.FieldInfo.class)) {
                classConfig.fields().forEach(f -> invocations.add(arguments(parameterTypes, f, messages)));
            } else {
                invocations.add(arguments(parameterTypes, classConfig, messages));
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

        private static Object[] arguments(Class<?>[] parameterTypes, Object config, Messages messages) {
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = parameterTypes[i].equals(Messages.class) ? messages : readOnlyOrConfig(parameterTypes[i], config);
            }
            return arguments;
        }

        /**
         * What an enhancement parameter is handed: the configuration, or — for a method that only reads — the
         * declaration the configuration is of.
         */
        private static Object readOnlyOrConfig(Class<?> parameterType, Object config) {
            if (parameterType.equals(jakarta.enterprise.lang.model.declarations.MethodInfo.class)
                && config instanceof MethodConfig methodConfig) {
                return methodConfig.info();
            }
            if (parameterType.equals(jakarta.enterprise.lang.model.declarations.FieldInfo.class)
                && config instanceof FieldConfig fieldConfig) {
                return fieldConfig.info();
            }
            if (parameterType.equals(jakarta.enterprise.lang.model.declarations.ClassInfo.class)
                && config instanceof ClassConfig cc) {
                return cc.info();
            }
            return config;
        }
    }

    /**
     * One method of one extension, and the priority it runs at within its phase.
     *
     * @param extension The extension
     * @param method    The method
     */
    private record ExtensionMethod(BuildCompatibleExtension extension, Method method) {

        private int priority() {
            jakarta.annotation.Priority priority = method.getAnnotation(jakarta.annotation.Priority.class);
            // the default of section 2.10: halfway through the application range
            return priority != null ? priority.value()
                : jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;
        }
    }
}
