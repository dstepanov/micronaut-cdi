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

import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import io.micronaut.cdi.internal.metadata.CdiScope;
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
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static volatile @org.jspecify.annotations.Nullable List<BuildCompatibleExtension> overriddenExtensions;
    private static volatile @org.jspecify.annotations.Nullable BuildCompatibleExtensionVisitor current;
    private static volatile io.micronaut.inject.visitor.@org.jspecify.annotations.Nullable VisitorContext
        activeContext;

    private static final String GENERATED = "io.micronaut.cdi.generated";
    private static final String TRIGGER = "RegistrationEnd";
    private static final String COMPONENTS = "SyntheticComponents";
    private static final String CONTEXTS = "RegisteredContexts";

    /**
     * What is kept from the moment a source is generated until the compiler comes to it: the visitor whose
     * phases a marker resumes, and the records of a factory's methods. A compiler is free to visit a generated
     * source with another instance of this visitor, so neither is kept on one.
     */
    private static final Map<String, BuildCompatibleExtensionVisitor> TRIGGERS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<String, AnnotationValue<?>>> FACTORY_RECORDS =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final List<Enhancer> enhancers = new ArrayList<>();
    private final List<Registrar> registrars = new ArrayList<>();
    private final List<ExtensionMethod> discoveries = new ArrayList<>();
    private final List<ExtensionMethod> synthesizers = new ArrayList<>();
    private final List<ExtensionMethod> validators = new ArrayList<>();
    private final DiscoveredClasses discovered = new DiscoveredClasses();
    private boolean discoveryRan;
    private boolean scannedImportWritten;
    private boolean contextRecordWritten;
    private boolean contextBeansWritten;
    private boolean importedClassesPending;
    private boolean synthesized;
    private boolean triggerWritten;
    private final TypeIndexCollector typeIndex = new TypeIndexCollector();
    private @org.jspecify.annotations.Nullable String suffix;

    public BuildCompatibleExtensionVisitor() {
        // what AnnotationBuilder.of composes with: the specification's resolver looks for it through the loader
        // of its own API, which is not always one that sees the service entry of this module
        jakarta.enterprise.inject.build.compatible.spi.BuildServicesResolver.setBuildServices(
            new ElementBuildServices());
        List<BuildCompatibleExtension> extensions = new ArrayList<>();
        List<BuildCompatibleExtension> overridden = overriddenExtensions;
        if (overridden != null) {
            extensions.addAll(overridden);
        } else {
            ServiceLoader.load(BuildCompatibleExtension.class, BuildCompatibleExtensionVisitor.class.getClassLoader())
                .forEach(extensions::add);
        }
        // the discovery phase runs once, as the compilation starts and before any class is visited: what it
        // says is about classes by name rather than about the class in front of the compiler, and is applied
        // as those classes come past. Within each phase the methods run by their priority, lowest first
        // (section 2.10)
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
        enhancements.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        registrations.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        synthesizers.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
        validators.sort(java.util.Comparator.comparingInt(ExtensionMethod::priority));
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
    public static void overrideExtensions(@org.jspecify.annotations.Nullable List<BuildCompatibleExtension> extensions) {
        overriddenExtensions = extensions;
    }

    /**
     * The visitor of the compilation under way, so that the registration visitor — which runs last, after
     * every other visitor has had its say — describes the beans with the same extensions and state.
     *
     * @return The visitor, or {@code null} outside a compilation
     */
    static @org.jspecify.annotations.Nullable BuildCompatibleExtensionVisitor current() {
        return current;
    }

    /**
     * The visitor context of the compilation under way, for the model classes that need the compiler's view of
     * a class the element at hand does not name — the implicit {@code java.lang.Object} superclass, say.
     *
     * @return The context, or {@code null} outside a compilation
     */
    static io.micronaut.inject.visitor.@org.jspecify.annotations.Nullable VisitorContext activeVisitorContext() {
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
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.ClassInfo.class)
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.MethodInfo.class)
                || parameterType.equals(jakarta.enterprise.lang.model.declarations.FieldInfo.class)) {
                queried++;
            } else if (!Phase.ENHANCEMENT.hands(parameterType)) {
                throw Phase.ENHANCEMENT.unsupported(method, parameterType);
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
            } else if (!Phase.REGISTRATION.hands(parameterType)) {
                throw Phase.REGISTRATION.unsupported(method, parameterType);
            }
        }
        if (queried != 1) {
            throw new jakarta.enterprise.inject.spi.DefinitionException("The @Registration method " + method
                + " must declare exactly one parameter naming what it is told about (section 2.10.3)");
        }
    }

    private static void validateHanded(Phase phase, Method method,
                                       @org.jspecify.annotations.Nullable Class<?> subject) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!parameterType.equals(subject) && !phase.hands(parameterType)) {
                throw phase.unsupported(method, parameterType);
            }
        }
    }

    /**
     * Runs one discovery method, handing it what it asked for.
     */
    private void discover(BuildCompatibleExtension extension, Method method, VisitorContext context) {
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
        if (contextRecordWritten || discovered.registeredQualifiers().isEmpty()) {
            return;
        }
        contextRecordWritten = true;
        GeneratedSource source = new GeneratedSource(context.getLanguage())
            .annotation("jakarta.inject.Singleton", null)
            .annotation("io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers",
                new GeneratedSource(context.getLanguage()).strings(discovered.registeredQualifiers()));
        write(context, "ExtensionContextRecordHolder", source, "The extension qualifier record");
    }

    /**
     * Declares a bean for every context class an extension registered (section 2.10.1), recording the scope
     * it serves: the container obtains the context from the bean as it starts.
     */
    private void writeContextBeans(ClassElement element, VisitorContext context) {
        if (contextBeansWritten || discovered.contexts().isEmpty()) {
            return;
        }
        contextBeansWritten = true;
        List<SynthesisPhase.Component> contexts = new ArrayList<>();
        discovered.contexts().forEach((scopeName, contextClasses) -> {
            for (String contextClass : contextClasses) {
                try {
                    contexts.add(new SynthesisPhase.Component(
                        SyntheticRecords.classElement(context, contextClass, "context"),
                        AnnotationValue.builder("io.micronaut.cdi.internal.metadata.CdiRegisteredContext")
                            .member("scope", new io.micronaut.core.annotation.AnnotationClassValue<>(scopeName))
                            .member("normal", discovered.isNormalContext(scopeName))
                            .build()));
                } catch (IllegalArgumentException e) {
                    context.fail(String.valueOf(e.getMessage()), element);
                }
            }
        });
        writeFactory(context, CONTEXTS + suffix, contexts);
    }

    /**
     * Writes a factory, in the language being compiled, with a method for each class that creates it, and
     * remembers what the bean of each method is to record.
     *
     * <p>A class an extension names - a creator, a disposer, a synthetic observer, a context - is compiled
     * elsewhere, and the one way to declare a bean of it that the compilers of all three languages process is
     * a source they compile themselves. The source says nothing but how the class is instantiated; what the
     * bean records is put on the method when the generated factory is visited, as annotation values rather
     * than as text.</p>
     */
    private void writeFactory(VisitorContext context, String className, List<SynthesisPhase.Component> components) {
        if (components.isEmpty()) {
            return;
        }
        boolean kotlin = context.getLanguage() == VisitorContext.Language.KOTLIN;
        StringBuilder source = new StringBuilder("package ").append(GENERATED).append(kotlin ? "" : ";").append("\n\n")
            .append("@io.micronaut.context.annotation.Factory\n")
            .append("@io.micronaut.cdi.internal.metadata.CdiExtensionComponents\n")
            .append(kotlin ? "class " : "final class ").append(className).append(" {\n");
        Map<String, AnnotationValue<?>> records = new java.util.LinkedHashMap<>();
        for (SynthesisPhase.Component component : components) {
            String method = "component" + records.size();
            String type = component.type().getCanonicalName();
            records.put(method, component.record());
            source.append("\n    @io.micronaut.context.annotation.Bean\n");
            if (kotlin) {
                source.append("    fun ").append(method).append("(): ").append(type).append(" = ").append(type)
                    .append("()\n");
            } else {
                source.append("    ").append(type).append(' ').append(method).append("() {\n        return new ")
                    .append(type).append("();\n    }\n");
            }
        }
        source.append("}\n");
        FACTORY_RECORDS.put(GENERATED + "." + className, records);
        writeSource(context, className, source.toString(), "The factory of the classes the extensions named");
    }

    /**
     * Puts on the methods of a generated factory what their beans are to record.
     */
    private static boolean recordOn(ClassElement element, VisitorContext context) {
        Map<String, AnnotationValue<?>> records = FACTORY_RECORDS.remove(element.getName());
        if (records == null) {
            if (element.getName().startsWith(GENERATED + "." + CONTEXTS)
                || element.getName().startsWith(GENERATED + "." + COMPONENTS)) {
                context.fail("The records of the generated factory " + element.getName() + " are gone: the "
                    + "compilation did not keep what the build compatible extensions described until the "
                    + "factory was compiled", element);
                return true;
            }
            return false;
        }
        for (io.micronaut.inject.ast.MethodElement method
            : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            AnnotationValue<?> record = records.get(method.getName());
            if (record != null) {
                method.annotate(record);
            }
        }
        return true;
    }

    /**
     * Writes the class whose compilation marks the moment every class of the application has been registered.
     *
     * <p>The phases that follow registration have to run after every class has been visited, and while the
     * compiler still accepts a source: the end of the compilation is too late in every language but one. A
     * generated source is compiled after the sources the compilation started with, in all three, so visiting
     * this one is that moment.</p>
     */
    private void writeTrigger(VisitorContext context, String className) {
        String end = context.getLanguage() == VisitorContext.Language.KOTLIN ? "" : ";";
        boolean kotlin = context.getLanguage() == VisitorContext.Language.KOTLIN;
        writeSource(context, className, "package " + GENERATED + end + "\n\n"
            + "@io.micronaut.cdi.internal.metadata.CdiExtensionComponents\n"
            + (kotlin ? "class " + className + "\n" : "final class " + className + " {\n}\n"),
            "The marker of the end of registration");
    }

    private static void writeSource(VisitorContext context, String className, String source, String what) {
        java.util.Optional<io.micronaut.inject.writer.GeneratedFile> generated =
            context.visitGeneratedSourceFile(GENERATED, className);
        if (generated.isEmpty()) {
            context.fail(what + " could not be written: the compilation accepts no generated source, and the "
                + "synthesis and validation phases of the build compatible extensions need one", null);
            return;
        }
        try {
            generated.get().write(writer -> writer.write(source));
        } catch (Exception e) {
            throw new IllegalStateException(what + " could not be written", e);
        }
    }

    private void writeScannedImport(VisitorContext context) {
        writeContextRecord(context);
        if (scannedImportWritten || discovered.scannedClasses().isEmpty()) {
            return;
        }
        scannedImportWritten = true;
        // the classes the import names are visited after it, and registered then
        importedClassesPending = true;
        // a class the discovery phase added to the scanned ones may say nothing at all on its own, and a class
        // with nothing on it is never handed to the bean machinery: a generated import names them all, and
        // its processing is what makes each a bean (their scope was put on as they were visited)
        GeneratedSource source = new GeneratedSource(context.getLanguage());
        source.annotation("io.micronaut.context.annotation.ClassImport",
            "classes = " + source.classes(discovered.scannedClasses()));
        write(context, "ScannedClassesImport", source, "The scanned classes import");
    }

    private static void write(VisitorContext context, String className, GeneratedSource source, String what) {
        context.visitGeneratedSourceFile("io.micronaut.cdi.generated", className)
            .ifPresent(file -> {
                try {
                    file.write(writer -> writer.write(source.classNamed(className)));
                } catch (Exception e) {
                    throw new IllegalStateException(what + " could not be written", e);
                }
            });
    }

    @Override
    public void start(VisitorContext context) {
        activeContext = context;
        io.micronaut.cdi.lang.model.ast.AstLanguageModel.useContext(context);
        // the visitor the compilation started is the one that visits its classes: a compiler may construct
        // others that it never starts
        current = this;
        if (!discoveryRan) {
            discoveryRan = true;
            discovered.compilation(context);
            for (ExtensionMethod discovery : discoveries) {
                discover(discovery.extension(), discovery.method(), context);
            }
        }
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
        io.micronaut.cdi.lang.model.ast.AstLanguageModel.useContext(context);
        // written as the first class is visited, so that the compiler still has rounds ahead of it to process
        // the generated import in
        String name = element.getName();
        if (name.startsWith(GENERATED + ".")) {
            if (name.startsWith(GENERATED + "." + TRIGGER)) {
                endOfRegistration(name, context);
                return;
            }
            if (recordOn(element, context)) {
                return;
            }
        } else if (TypeIndexCollector.recordOn(element)) {
            return;
        } else {
            if (suffix == null) {
                // what this compilation generates is named after the first class it compiles, so that two
                // compilations of one application do not generate the same class
                suffix = Integer.toHexString(name.hashCode());
            }
            typeIndex.collect(element);
            if (!triggerWritten && (!typeIndex.isEmpty()
                || !synthesizers.isEmpty() || !validators.isEmpty() || !registrars.isEmpty())) {
                // something waits for every class to have come past
                triggerWritten = true;
                TRIGGERS.put(GENERATED + "." + TRIGGER + suffix, this);
                writeTrigger(context, TRIGGER + suffix);
            }
        }
        writeScannedImport(context);
        if (suffix != null) {
            writeContextBeans(element, context);
        }
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
     * The generated marker has come past: every class the compilation started with has been registered. Where
     * a generated import named more classes, they come past with or after this marker, so a second marker is
     * written and the phases wait for it.
     */
    private static void endOfRegistration(String trigger, VisitorContext context) {
        BuildCompatibleExtensionVisitor visitor = TRIGGERS.remove(trigger);
        if (visitor == null) {
            context.fail("The compilation did not keep the build compatible extensions until the classes of the "
                + "application had been registered: the synthesis and validation phases cannot run", null);
            return;
        }
        if (visitor.importedClassesPending) {
            visitor.importedClassesPending = false;
            String next = TRIGGER + "Again" + visitor.suffix;
            TRIGGERS.put(GENERATED + "." + next, visitor);
            visitor.writeTrigger(context, next);
            return;
        }
        if (visitor.suffix != null) {
            visitor.typeIndex.write(context, visitor.suffix);
        }
        visitor.synthesise(context);
    }

    /**
     * Fails the compilation where the phases that follow registration never ran: nothing is left out
     * silently. The Groovy and Kotlin compilers finish once, when nothing more is compiled.
     */
    @Override
    public void finish(VisitorContext context) {
        // a Java compilation finishes every round, and always compiles a source a round generated
        if (context.getLanguage() != VisitorContext.Language.JAVA
            && triggerWritten && !synthesized && TRIGGERS.containsValue(this)) {
            TRIGGERS.values().remove(this);
            context.fail("The generic hierarchies of the compiled classes were not recorded, and the synthesis and "
                + "validation phases of the build compatible extensions did not run: the compilation never "
                + "compiled the source generated to mark the end of registration", null);
        }
    }

    /**
     * Runs the phases that follow the registration of the compiled beans, once every class of the compilation
     * has been visited: the synthesis of section 2.10.5, the registration of what it described, and the
     * validation of section 2.10.6.
     */
    private void synthesise(VisitorContext context) {
        activeContext = context;
        io.micronaut.cdi.lang.model.ast.AstLanguageModel.useContext(context);
        synthesized = true;
        if (synthesizers.isEmpty() && validators.isEmpty() && registrars.isEmpty()) {
            return;
        }
        // what an extension reports from here on is about the deployment as a whole rather than about a
        // definition the compiler is looking at
        Messages messages = VisitorMessages.ofTheDeployment(context);
        SynthesisPhase synthesis = new SynthesisPhase(context, discovered);
        for (ExtensionMethod synthesizer : synthesizers) {
            Class<?>[] parameterTypes = synthesizer.method().getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = parameterTypes[i].equals(SyntheticComponents.class)
                    ? synthesis.componentsOf(synthesizer.extension())
                    : Phase.SYNTHESIS.argument(parameterTypes[i], messages, context);
            }
            if (!invokeLatePhase("synthesis", synthesizer, arguments, context)) {
                return;
            }
        }
        try {
            writeFactory(context, COMPONENTS + suffix, synthesis.components());
        } catch (IllegalArgumentException | IllegalStateException e) {
            context.fail(VisitorMessages.DEPLOYMENT_PROBLEM + e.getMessage(), null);
            return;
        }
        // the beans the compiler saw were described to the registration phase as they were compiled; what
        // is left is the ones it did not see: the synthetic ones, and the container's own
        for (RecordingBeanBuilder<?> bean : synthesis.enabledBeans()) {
            SyntheticBeanInfo info = synthesis.describe(bean);
            for (Registrar registrar : registrars) {
                if (registrar.asksForBeans(false) && registrar.matches(info.beanType())) {
                    registrar.describe(info, messages, context);
                }
            }
        }
        for (RecordingObserverBuilder<?> observer : synthesis.observers()) {
            SyntheticObserverInfo info = new SyntheticObserverInfo(observer);
            ClassElement eventType = ElementTypes.elementOf(observer.eventType());
            for (Registrar registrar : registrars) {
                if (registrar.asksForObservers() && registrar.matches(eventType)) {
                    registrar.describeObserver(info, messages, context);
                }
            }
        }
        // the built-in beans are beans of the application too (section 2.10.3): no class of the compilation
        // declares them, so the phase is told about them here
        context.getClassElement("io.micronaut.cdi.runtime.CdiBeanContainer").ifPresent(container -> {
            ElementBeanInfo builtIn = new ElementBeanInfo(container, null);
            for (Registrar registrar : registrars) {
                if (registrar.matches(builtIn)) {
                    registrar.describe(builtIn, messages, context);
                }
            }
        });
        // and the validation phase is the last word on it
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
            if (cause instanceof jakarta.enterprise.inject.spi.DefinitionException) {
                context.fail("The " + phase + " method " + method + " failed: " + cause, null);
            } else {
                context.fail(VisitorMessages.DEPLOYMENT_PROBLEM + "the " + phase + " method " + method
                    + " failed: " + cause, null);
            }
        }
        return false;
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
        io.micronaut.cdi.lang.model.ast.AstLanguageModel.useContext(context);
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
            && !element.getAnnotationMetadata().hasStereotype("io.micronaut.cdi.internal.metadata.CdiScope")) {
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
            return asksForBeans(true) && matches(bean.beanType());
        }

        /**
         * Whether the method asks to be told about beans: about any bean, or - where interceptors count -
         * about interceptors.
         */
        private boolean asksForBeans(boolean orInterceptors) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(BeanInfo.class) || (orInterceptors && parameterType.equals(
                    jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo.class))) {
                    return true;
                }
            }
            return false;
        }

        private boolean asksForObservers() {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
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
            return asksForObservers() && matches(observer.observedParameter().getGenericType());
        }

        private void describeObserver(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer,
                                      Messages messages, VisitorContext context) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
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
                        jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo.class)
                        && bean instanceof jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo)) {
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
            for (io.micronaut.inject.ast.MethodElement method
                : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
                if (memberCarries(method, annotation, any)) {
                    return true;
                }
            }
            for (io.micronaut.inject.ast.ConstructorElement constructor
                : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
                if (memberCarries(constructor, annotation, any)) {
                    return true;
                }
            }
            for (io.micronaut.inject.ast.FieldElement field
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

        private static boolean memberCarries(io.micronaut.inject.ast.MethodElement method, String annotation,
                                             boolean any) {
            if (any
                ? !method.getDeclaredAnnotationNames().isEmpty()
                : method.hasDeclaredAnnotation(annotation)
                    || carriedAsMetaAnnotation(method.getAnnotationMetadata(), annotation)) {
                return true;
            }
            for (io.micronaut.inject.ast.ParameterElement parameter : method.getParameters()) {
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
        private static boolean carriedAsMetaAnnotation(io.micronaut.core.annotation.AnnotationMetadata metadata,
                                                       String annotation) {
            // what carries the asked-for annotation as a meta-annotation: the query answers the annotations
            // that lead to it, and any of them being declared here is what counts
            java.util.List<String> carriers = metadata.getAnnotationNamesByStereotype(annotation);
            return metadata.getDeclaredAnnotationNames().stream().anyMatch(carriers::contains);
        }

        private void enhance(ClassElement element, Messages messages, VisitorContext context) {
            ElementClassConfig classConfig = new ElementClassConfig(element);
            Class<?>[] parameterTypes = method.getParameterTypes();
            // a method taking a configuration of a member is invoked once for each of those members, and one
            // taking a configuration of the class once for the class
            List<Object[]> invocations = new ArrayList<>();
            if (takes(parameterTypes, MethodConfig.class)
                || takes(parameterTypes, jakarta.enterprise.lang.model.declarations.MethodInfo.class)) {
                classConfig.methods().forEach(m -> invocations.add(arguments(parameterTypes, m, messages, context)));
            } else if (takes(parameterTypes, FieldConfig.class)
                || takes(parameterTypes, jakarta.enterprise.lang.model.declarations.FieldInfo.class)) {
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
     * What each phase hands an extension method besides what the method is about (sections 2.10.1 to 2.10.3):
     * the one list that both the validation of a method and its invocation read.
     */
    private enum Phase {
        DISCOVERY("@Discovery", "2.10.1", Messages.class),
        ENHANCEMENT("@Enhancement", "2.10.2", Messages.class,
            jakarta.enterprise.inject.build.compatible.spi.Types.class),
        REGISTRATION("@Registration", "2.10.3", Messages.class,
            jakarta.enterprise.inject.build.compatible.spi.Types.class,
            jakarta.enterprise.inject.build.compatible.spi.InvokerFactory.class),
        SYNTHESIS("@Synthesis", "2.10.4", Messages.class,
            jakarta.enterprise.inject.build.compatible.spi.Types.class),
        VALIDATION("@Validation", "2.10.5", Messages.class,
            jakarta.enterprise.inject.build.compatible.spi.Types.class);

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
                        @org.jspecify.annotations.Nullable VisitorContext context) {
            if (parameterType.equals(Messages.class)) {
                return messages;
            }
            if (context != null && hands(parameterType)) {
                if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                    return new VisitorTypes(context);
                }
                if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.InvokerFactory.class)) {
                    return new ElementInvokerFactory();
                }
            }
            throw new IllegalStateException("The " + annotation + " phase does not hand a "
                + parameterType.getName());
        }

        jakarta.enterprise.inject.spi.DefinitionException unsupported(Method method, Class<?> parameterType) {
            return new jakarta.enterprise.inject.spi.DefinitionException("The " + annotation + " method " + method
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
    private record ExtensionMethod(BuildCompatibleExtension extension, Method method) {

        private int priority() {
            jakarta.annotation.Priority priority = method.getAnnotation(jakarta.annotation.Priority.class);
            // the default of section 2.10: halfway through the application range
            return priority != null ? priority.value()
                : jakarta.interceptor.Interceptor.Priority.APPLICATION + 500;
        }
    }

    /**
     * An empty, annotated class in the syntax of the language being compiled: the generated file takes the
     * language's extension, so it has to read in that language.
     */
    private static final class GeneratedSource {

        private final VisitorContext.Language language;
        private final StringBuilder annotations = new StringBuilder();

        GeneratedSource(VisitorContext.Language language) {
            this.language = language;
        }

        GeneratedSource annotation(String type, @org.jspecify.annotations.Nullable String arguments) {
            annotations.append('@').append(type);
            if (arguments != null) {
                annotations.append('(').append(arguments).append(')');
            }
            annotations.append('\n');
            return this;
        }

        /** The value member as an array of string literals, named: Kotlin reads an unnamed array as varargs. */
        String strings(java.util.Collection<String> values) {
            List<String> literals = new ArrayList<>(values.size());
            for (String value : values) {
                literals.add('"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
            }
            return "value = " + array(literals);
        }

        /** An array of class literals. */
        String classes(java.util.Collection<String> names) {
            List<String> literals = new ArrayList<>(names.size());
            for (String name : names) {
                literals.add(language == VisitorContext.Language.KOTLIN ? name + "::class" : name + ".class");
            }
            return array(literals);
        }

        private String array(List<String> literals) {
            String open = language == VisitorContext.Language.JAVA ? "{" : "[";
            String close = language == VisitorContext.Language.JAVA ? "}" : "]";
            return open + String.join(", ", literals) + close;
        }

        String classNamed(String name) {
            boolean kotlin = language == VisitorContext.Language.KOTLIN;
            return "package io.micronaut.cdi.generated" + (kotlin ? "" : ";") + "\n\n" + annotations
                + (kotlin ? "class " + name + "\n" : "final class " + name + " {\n}\n");
        }
    }
}
