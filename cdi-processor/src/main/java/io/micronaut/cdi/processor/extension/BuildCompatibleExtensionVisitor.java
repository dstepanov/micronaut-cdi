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

import io.micronaut.cdi.internal.metadata.CdiDeploymentRecord;
import io.micronaut.cdi.internal.metadata.CdiExtensionComponents;
import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers;
import io.micronaut.cdi.internal.metadata.CdiRegisteredContext;
import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.cdi.processor.mapper.NonbindingMembersRemapper;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import io.micronaut.cdi.internal.metadata.CdiScope;
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

    /**
     * The extensions of one deployment, set by a harness that compiles a deployment at a time: the one seam a
     * compilation run inside a test has for handing a visitor objects, where a compiler takes nothing but options.
     */
    private static volatile @org.jspecify.annotations.Nullable Deployment overriddenDeployment;

    private static final String GENERATED = "io.micronaut.cdi.generated";
    private static final String SCANNED_CLASSES = "ScannedClassesImport";
    private static final int MAX_COMPILATIONS_OF_A_MODULE = 16;

    /**
     * What the visitor that started a compilation is kept under, on the compilation (see {@link CompilationState}).
     */
    private static final String VISITOR = "visitor";

    private final List<Enhancer> enhancers = new ArrayList<>();
    private final List<Registrar> registrars = new ArrayList<>();
    private final List<ExtensionMethod> discoveries = new ArrayList<>();
    private final List<ExtensionMethod> synthesizers = new ArrayList<>();
    private final List<ExtensionMethod> validators = new ArrayList<>();
    private final @org.jspecify.annotations.Nullable Deployment deployment;
    private final DiscoveredClasses discovered = new DiscoveredClasses();
    private boolean discoveryRan;
    /**
     * Whether the scanned classes have been seen to: imported, under javac, or registered.
     */
    private boolean scannedClassesHandled;
    private boolean synthesized;
    /**
     * Whether a round of the compilation has finished: a scanned class visited after that is one the generated
     * import named, of a library rather than of the compilation.
     */
    private boolean roundFinished;
    /**
     * The classes described to the registration phase already: a class the discovery phase scanned is visited as
     * it is compiled and again as the generated import names it.
     */
    private final java.util.Set<String> registered = new java.util.HashSet<>();
    private final TypeIndexCollector typeIndex = new TypeIndexCollector();
    /**
     * The classes the records of the deployment were registered from, which a later round does not register one from.
     */
    private final TypeIndexCollector.RecordOrigins recordOrigins = new TypeIndexCollector.RecordOrigins();
    /**
     * The classes of the compilation this visitor has visited, in the order it visited them: what it registers and
     * generates is written from them, and the first names it.
     */
    private final List<ClassElement> contributors = new ArrayList<>();
    private final java.util.Set<String> visited = new java.util.HashSet<>();
    private @org.jspecify.annotations.Nullable String suffix;

    public BuildCompatibleExtensionVisitor() {
        List<BuildCompatibleExtension> extensions = new ArrayList<>();
        Deployment overridden = overriddenDeployment;
        deployment = overridden;
        if (overridden != null) {
            extensions.addAll(overridden.extensions());
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
        overrideExtensions(extensions, scanned -> {
        });
    }

    /**
     * Hands this visitor the extensions of one deployment, and has it report the classes their discovery phase
     * added to the scanned ones: an archive without a beans.xml has no discovered beans beyond these, which the
     * harness that compiles it is the one to apply. Setting {@code null} puts the service loading back.
     *
     * @param extensions     The extensions, or {@code null} to load them as usual
     * @param scannedClasses Handed the names of the classes the discovery phase added, once it has run
     */
    public static void overrideExtensions(@org.jspecify.annotations.Nullable List<BuildCompatibleExtension> extensions,
                                          java.util.function.Consumer<java.util.Set<String>> scannedClasses) {
        overriddenDeployment = extensions == null ? null : new Deployment(List.copyOf(extensions), scannedClasses);
    }

    /**
     * The visitor that started the given compilation, so that the registration visitor — which runs last, after
     * every other visitor has had its say — describes the beans with the same extensions and state.
     *
     * @param context The compilation
     * @return The visitor, or {@code null} where none started it
     */
    static @org.jspecify.annotations.Nullable BuildCompatibleExtensionVisitor of(VisitorContext context) {
        return CompilationState.get(context, VISITOR, BuildCompatibleExtensionVisitor.class);
    }

    /**
     * What the discovery phase of the given compilation said, for the annotation remapping that records the
     * non-binding members of a qualifier or binding an extension configured: it runs as the metadata of each
     * element is built, outside any visitor, and is handed the compilation it builds it for.
     *
     * @param context The compilation
     * @return What was discovered, or {@code null} where no visitor of this kind started the compilation
     */
    public static @org.jspecify.annotations.Nullable DiscoveredClasses discoveryOf(VisitorContext context) {
        BuildCompatibleExtensionVisitor visitor = of(context);
        return visitor == null ? null : visitor.discovered;
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
    public io.micronaut.inject.visitor.TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return io.micronaut.inject.visitor.TypeElementQuery.onlyClass();
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

    /**
     * The classes of the compilation this visitor has visited, which what it registers and generates is written
     * from.
     */
    io.micronaut.inject.ast.Element[] contributors() {
        return contributors.toArray(new io.micronaut.inject.ast.Element[0]);
    }

    /**
     * Opens a source this visitor generates: the import of the scanned classes, written from what the classes of the
     * compilation said rather than from one class.
     *
     * <p>Under javac the visitor stays isolating, because only the isolating processor runs it before the other
     * visitors, as an enhancement has to be, and the source is written with no originating element: Gradle then
     * recompiles the whole source set rather than part of it, which is what the source needs. Under KSP every
     * class the compilation has visited so far is named as an origin: a source that names none is dropped as
     * soon as any file is deleted, with nothing left to regenerate it, while one that names them is generated
     * again, the other classes it names processed again with it, whenever one of them changes or goes.</p>
     */
    static java.util.Optional<io.micronaut.inject.writer.GeneratedFile> generatedSource(
        VisitorContext context, String packageName, String className, io.micronaut.inject.ast.Element[] origins) {
        if (context.getLanguage() == VisitorContext.Language.KOTLIN) {
            return context.visitGeneratedSourceFile(packageName, className, origins);
        }
        return context.visitGeneratedSourceFile(packageName, className);
    }

    @Override
    public void start(VisitorContext context) {
        // what AnnotationBuilder.of composes with, reading the annotation interfaces through this compilation. The
        // specification's resolver is the static entry point of its build time API, which an extension calls with
        // nothing to hand it; it is set here, as the compilation starts, rather than left to the service loader,
        // which looks through the loader of the API and does not always see the service entry of this module
        jakarta.enterprise.inject.build.compatible.spi.BuildServicesResolver.setBuildServices(
            new ElementBuildServices(context));
        // the visitor the compilation started is the one that visits its classes: a compiler may construct
        // others that it never starts
        CompilationState.keep(context, VISITOR, this);
        if (!discoveryRan) {
            discoveryRan = true;
            discovered.compilation(context);
            for (ExtensionMethod discovery : discoveries) {
                discover(discovery.extension(), discovery.method(), context);
            }
            Deployment overridden = deployment;
            if (overridden != null) {
                overridden.scannedClasses().accept(java.util.Set.copyOf(discovered.scannedClasses()));
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
        String name = element.getName();
        if (name.startsWith(GENERATED + ".")) {
            // a class this visitor generated: the import of the scanned classes
            return;
        }
        // a scanned class the generated import brought in a later round is a class of a library: nothing the
        // compilation writes is named after it or placed in its package
        boolean owned = !(roundFinished && discovered.scannedClasses().contains(name));
        if (visited.add(name) && owned) {
            contributors.add(element);
        }
        typeIndex.collect(element, owned);
        applyWhatWasDiscovered(element);
        enhance(element, context);
    }

    private void enhance(ClassElement element, VisitorContext context) {
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
     * Runs what waits for every class of the compilation to have been registered, as the compiler finishes the
     * first round that visited any: the registration of the scanned classes the compiler does not make beans of,
     * of the context classes and of the registered qualifiers, and the synthesis of section 2.10.5, the registration
     * of what it described, and the validation of section 2.10.6. The generic hierarchies of the classes of each round
     * are recorded as that round finishes.
     *
     * <p>A round is finished once every visitor has visited every class of it, the registration visitor - which
     * runs last - included, and before the beans of the round are written, in javac, KSP and Groovy alike: what is
     * registered here is written with them, and needs no source. Under javac, where the scanned classes are imported
     * rather than registered, the phases wait for the round that visits them. They run once: a later round finds them
     * run.</p>
     */
    @Override
    public void finishRound(VisitorContext context) {
        if (contributors.isEmpty() || of(context) != this) {
            return;
        }
        roundFinished = true;
        try {
            finishThisRound(context);
        } finally {
            recordOrigins.roundFinished();
        }
    }

    private void finishThisRound(VisitorContext context) {
        if (synthesized) {
            // the generic hierarchies of the classes a later round brought - sources another processor generated -
            // are recorded as that round finishes
            typeIndex.write(context, recordOrigins, contributors);
            return;
        }
        if (!scannedClassesHandled) {
            scannedClassesHandled = true;
            if (importScannedClasses(context)) {
                // javac visits the imported classes in the next round, describes them to the registration phase,
                // and finishes that round with the phases that follow
                return;
            }
            registerScannedClasses(context);
        }
        synthesized = true;
        registerContexts(context);
        registerQualifiers(context);
        // the generic hierarchies of the classes so far, the scanned classes javac imported among them: recorded in
        // the round the records of the deployment are registered in, which numbers the definitions registered from
        // one class, rather than in an earlier one
        typeIndex.write(context, recordOrigins, contributors);
        synthesise(context);
    }

    /**
     * Under javac, writes an import of the classes the discovery phase added to the scanned ones.
     *
     * <p>A class the discovery phase added may say nothing at all about itself, and javac hands the bean machinery
     * the classes that carry an annotation it reacts to and no others. Which of the scanned classes it makes beans
     * of on their own is decided after the visitors have run, so a bean registered for each here would stand
     * beside the definition javac writes for some: the import names them all, and javac makes each a bean once,
     * the whole bean machinery processing it, with the scope it was given as it was visited. KSP and Groovy hand
     * the bean machinery every class they compile, and read no import (see {@link #registerScannedClasses}).</p>
     *
     * @return Whether the import was written, and the classes it names are visited in the next round
     */
    private boolean importScannedClasses(VisitorContext context) {
        if (context.getLanguage() != VisitorContext.Language.JAVA || discovered.scannedClasses().isEmpty()) {
            return false;
        }
        List<String> literals = new ArrayList<>();
        for (String scanned : discovered.scannedClasses()) {
            // a class literal names a nested class by its canonical name, never by its binary one
            literals.add(context.getClassElement(scanned).map(ClassElement::getCanonicalName).orElse(scanned)
                + ".class");
        }
        String className = SCANNED_CLASSES + suffix(context);
        String source = "package " + GENERATED + ";\n\n"
            + "@io.micronaut.context.annotation.ClassImport(classes = {" + String.join(", ", literals) + "})\n"
            + "final class " + className + " {\n}\n";
        java.util.Optional<io.micronaut.inject.writer.GeneratedFile> file =
            generatedSource(context, GENERATED, className, contributors());
        if (file.isEmpty()) {
            context.fail("The import of the scanned classes could not be written: the compilation accepts no "
                + "generated source", null);
            return false;
        }
        try {
            file.get().write(writer -> writer.write(source));
        } catch (Exception e) {
            throw new IllegalStateException("The import of the scanned classes could not be written", e);
        }
        return true;
    }

    /**
     * Under KSP and Groovy, registers a bean of each class the discovery phase added to the scanned ones that the
     * compilation does not compile and that has no definition of its own.
     *
     * <p>A class the compilation compiles is handed to the bean machinery as every class is, and is a bean with
     * the scope it was given as it was visited. A class of a library is not, and is put through what a class of
     * the compilation is put through here: what the discovery phase said of it, the enhancement and the
     * registration phases, and then a bean registered for it, with its injection points.</p>
     */
    private void registerScannedClasses(VisitorContext context) {
        Messages messages = new VisitorMessages(context);
        for (String scanned : discovered.scannedClasses()) {
            int nested = scanned.indexOf('$');
            if (visited.contains(scanned) || (nested > 0 && visited.contains(scanned.substring(0, nested)))) {
                continue;
            }
            ClassElement element = context.getClassElement(scanned).orElse(null);
            if (element == null || element.isAbstract() || element.isInterface() || hasDefinition(element, context)) {
                continue;
            }
            applyWhatWasDiscovered(element);
            enhance(element, context);
            // what each class of the compilation is put through as it is visited: its scope, its injection points
            // marked for the container, its bean types, its producers and observers, its default qualifier
            for (io.micronaut.inject.visitor.TypeElementVisitor<?, ?> visitor : classVisitors()) {
                if (!(visitor instanceof io.micronaut.cdi.processor.visitor.InjectionPointRulesVisitor)) {
                    // the rules a class is held to as it compiles are its own compilation's to judge; and a compiler
                    // that reads a class of a library from its bytes reads less of it than of a source
                    visitor.visitClass(element, context);
                }
            }
            register(element, messages, context);
            java.util.Map<String, io.micronaut.inject.ast.MemberElement> producers = new java.util.HashMap<>();
            for (io.micronaut.inject.ast.MemberElement producer
                : io.micronaut.cdi.processor.visitor.SpecificationBeans.producersOf(element)) {
                producers.put(producerKey(producer), producer);
            }
            context.registerBean(element, contributors()).inject()
                .produceBeans(ElementQuery.ALL_METHODS.onlyDeclared()
                    .annotated(metadata -> metadata.hasDeclaredAnnotation(io.micronaut.cdi.processor.Cdi.PRODUCES)),
                    produced -> describedAsVisited(produced, producers))
                .produceBeans(ElementQuery.ALL_FIELDS.onlyDeclared()
                    .annotated(metadata -> metadata.hasDeclaredAnnotation(io.micronaut.cdi.processor.Cdi.PRODUCES)),
                    produced -> describedAsVisited(produced, producers));
        }
    }

    /**
     * Gives the bean a producer of a scanned class of a library produces what the visitors recorded of the producer:
     * the builder reads the member anew, without what they wrote on it.
     */
    private static void describedAsVisited(io.micronaut.inject.ast.beans.BeanElementBuilder produced,
                                           java.util.Map<String, io.micronaut.inject.ast.MemberElement> producers) {
        if (!(produced.getProducingElement() instanceof io.micronaut.inject.ast.MemberElement member)) {
            return;
        }
        io.micronaut.inject.ast.MemberElement visited = producers.get(producerKey(member));
        if (visited == null) {
            return;
        }
        io.micronaut.core.annotation.AnnotationMetadata recorded = visited.getAnnotationMetadata().getDeclaredMetadata();
        for (String name : recorded.getAnnotationNames()) {
            io.micronaut.core.annotation.AnnotationValue<java.lang.annotation.Annotation> value = recorded.getAnnotation(name);
            if (value != null && !produced.hasDeclaredAnnotation(name)) {
                produced.annotate(value);
            }
        }
    }

    private static String producerKey(io.micronaut.inject.ast.MemberElement member) {
        if (member instanceof io.micronaut.inject.ast.MethodElement method) {
            StringBuilder key = new StringBuilder(method.getName()).append('(');
            for (io.micronaut.inject.ast.ParameterElement parameter : method.getParameters()) {
                key.append(parameter.getType().getName()).append(',');
            }
            return key.append(')').toString();
        }
        return member.getName();
    }

    /**
     * The visitors of this module that read one class at a time, in the order Micronaut runs them in.
     */
    private static List<io.micronaut.inject.visitor.TypeElementVisitor<?, ?>> classVisitors() {
        List<io.micronaut.inject.visitor.TypeElementVisitor<?, ?>> visitors = new ArrayList<>(List.of(
            new io.micronaut.cdi.processor.visitor.CdiScopeVisitor(),
            new io.micronaut.cdi.processor.visitor.ContainerInjectionVisitor(),
            new io.micronaut.cdi.processor.visitor.BeanNameVisitor(),
            new io.micronaut.cdi.processor.visitor.BeanDiscoveryVisitor(),
            new io.micronaut.cdi.processor.visitor.ProducerVisitor(),
            new io.micronaut.cdi.processor.visitor.ObserverVisitor(),
            new io.micronaut.cdi.processor.visitor.DefaultQualifierVisitor(),
            new io.micronaut.cdi.processor.visitor.InjectionPointRulesVisitor(),
            new io.micronaut.cdi.processor.visitor.ProxyabilityVisitor(),
            new io.micronaut.cdi.processor.visitor.BeanTypesVisitor()));
        io.micronaut.core.order.OrderUtil.reverseSort(visitors);
        return visitors;
    }

    /**
     * Whether the class was compiled with Micronaut's bean definition of it beside it.
     */
    private static boolean hasDefinition(ClassElement element, VisitorContext context) {
        String packageName = element.getPackageName();
        String simpleName = packageName.isEmpty() ? element.getName()
            : element.getName().substring(packageName.length() + 1);
        return context.getClassElement((packageName.isEmpty() ? "" : packageName + ".") + "$" + simpleName
            + "$Definition").isPresent();
    }

    /**
     * Declares a bean for every context class an extension registered (section 2.10.1), recording the scope
     * it serves: the container obtains the context from the bean as it starts.
     */
    private void registerContexts(VisitorContext context) {
        discovered.contexts().forEach((scopeName, contextClasses) -> {
            for (String contextClass : contextClasses) {
                try {
                    registerComponent(context, new SynthesisPhase.Component(
                        SyntheticRecords.classElement(context, contextClass, "context"),
                        AnnotationValue.builder(CdiRegisteredContext.class)
                            .member("scope", new io.micronaut.core.annotation.AnnotationClassValue<>(scopeName))
                            .member("normal", discovered.isNormalContext(scopeName))
                            .build()));
                } catch (IllegalArgumentException e) {
                    context.fail(String.valueOf(e.getMessage()), contributors.get(0));
                }
            }
        });
    }

    /**
     * Records the annotations the extensions made qualifiers of, on a bean registered for the purpose: no bean
     * may use such an annotation, so the record is the one witness of it the container has.
     */
    private void registerQualifiers(VisitorContext context) {
        if (discovered.registeredQualifiers().isEmpty()) {
            return;
        }
        ClassElement record = context.getClassElement(CdiDeploymentRecord.class.getName()).orElse(null);
        if (record == null) {
            context.fail("The qualifiers the build compatible extensions registered cannot be recorded: "
                + CdiDeploymentRecord.class.getName() + ", of micronaut-cdi, is not on the classpath of the "
                + "compilation", contributors.get(0));
            return;
        }
        ClassElement origin = recordOrigins.available(contributors);
        if (origin == null) {
            throw new IllegalStateException("Every class of the compilation is the origin of an earlier record");
        }
        recordOrigins.use(origin);
        List<io.micronaut.inject.ast.Element> origins = new ArrayList<>(contributors.size());
        origins.add(origin);
        for (ClassElement contributor : contributors) {
            if (contributor != origin) {
                origins.add(contributor);
            }
        }
        context.registerBean(record, origins.toArray(new io.micronaut.inject.ast.Element[0]))
            .annotate(AnnotationValue.builder(CdiExtensionComponents.class).build())
            .annotate(AnnotationValue.builder(CdiExtensionQualifiers.class)
                .values(discovered.registeredQualifiers().toArray(new String[0]))
                .build());
    }

    /**
     * Declares a bean of a class an extension named - a creator, a disposer, a synthetic observer, a context -
     * with the record of what it stands for on its definition, which the container finds it by.
     *
     * <p>The class is compiled elsewhere, often with the extension, and the bean is registered for it without a
     * source: its definition is written with the beans of the round, named after the first class of the
     * compilation, and every class of the compilation is its origin, as the round as a whole is what described
     * it. The class is instantiated through its constructor without parameters, as the specification has it.</p>
     */
    private void registerComponent(VisitorContext context, SynthesisPhase.Component component) {
        BeanElementBuilder bean = context.registerBean(component.type(), contributors());
        component.type().getDefaultConstructor().ifPresent(bean::createWith);
        bean.annotate(component.record());
    }

    /**
     * What the classes this compilation generates are named with, chosen as they are first generated.
     */
    private String suffix(VisitorContext context) {
        String chosen = suffix;
        if (chosen == null) {
            chosen = suffixOf(contributors.get(0).getName(), context);
            suffix = chosen;
        }
        return chosen;
    }

    /**
     * What the classes this compilation generates are named with, so that two compilations of one application -
     * two modules, or the main and the test classes of one - do not generate the same class.
     *
     * <p>It has to be the same from one build to the next: an incremental build that generated the classes under
     * another name would leave the definitions Micronaut wrote for the old ones behind, naming classes that are
     * gone. Where the build names the module being compiled, as the Micronaut Gradle plugin does,
     * the name is made from that, and from how many compilations of the same module are on the class path
     * already - the main classes are, to the test classes. Otherwise it is made from the first class the
     * compilation compiles, which a build that compiles everything at once comes to first every time.</p>
     */
    private static String suffixOf(String firstClass, VisitorContext context) {
        Map<String, String> options = context.getOptions();
        String group = options.get(VisitorContext.MICRONAUT_PROCESSING_GROUP);
        String module = options.get(VisitorContext.MICRONAUT_PROCESSING_MODULE);
        if (module == null || module.isEmpty()) {
            return Integer.toHexString(firstClass.hashCode());
        }
        String compilation = group + ":" + module;
        for (int taken = 0; taken < MAX_COMPILATIONS_OF_A_MODULE; taken++) {
            String candidate = Integer.toHexString(
                (taken == 0 ? compilation : compilation + "#" + taken).hashCode());
            if (isUnused(candidate, context)) {
                return candidate;
            }
        }
        return Integer.toHexString(firstClass.hashCode());
    }

    /**
     * Whether the class the compilation could generate under the suffix, the import of the scanned classes, is not
     * on the class path already.
     */
    private static boolean isUnused(String suffix, VisitorContext context) {
        return context.getClassElement(GENERATED + "." + SCANNED_CLASSES + suffix).isEmpty();
    }

    /**
     * Fails the compilation where the phases that follow registration never ran: nothing is left out
     * silently. The Groovy and Kotlin compilers finish once, when nothing more is compiled.
     */
    @Override
    public void finish(VisitorContext context) {
        // a Java compilation finishes every round
        if (context.getLanguage() == VisitorContext.Language.JAVA
            || CompilationState.get(context, VISITOR, BuildCompatibleExtensionVisitor.class) != this) {
            return;
        }
        if (!synthesized && !contributors.isEmpty()) {
            context.fail("The generic hierarchies of the compiled classes were not recorded, and the synthesis and "
                + "validation phases of the build compatible extensions did not run: the compiler finished no "
                + "round of the compilation", null);
        }
        // the Groovy compiler keeps the attributes of every compilation in one static map, which would hold
        // the visitor, and everything it read, for as long as the process runs
        CompilationState.take(context, VISITOR, BuildCompatibleExtensionVisitor.class);
    }

    /**
     * Runs the phases that follow the registration of the compiled beans, once every class of the compilation
     * has been visited: the synthesis of section 2.10.5, the registration of what it described, and the
     * validation of section 2.10.6.
     */
    private void synthesise(VisitorContext context) {
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
            for (SynthesisPhase.Component component : synthesis.components()) {
                registerComponent(context, component);
            }
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
            SyntheticObserverInfo info = new SyntheticObserverInfo(observer, context);
            ClassElement eventType = ElementTypes.elementOf(observer.eventType());
            for (Registrar registrar : registrars) {
                if (registrar.asksForObservers() && registrar.matches(eventType)) {
                    registrar.describeObserver(info, messages, context);
                }
            }
        }
        // the built-in beans are beans of the application too (section 2.10.3): no class of the compilation
        // declares them, so the phase is told about them here
        context.getClassElement("io.micronaut.cdi.internal.runtime.CdiBeanContainer").ifPresent(container -> {
            ElementBeanInfo builtIn = ElementBeanInfo.of(container, null, context);
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
     * else has left it. A class that cannot be a bean - one vetoed, an abstract class, an interface, one with no
     * bean constructor (section 3.1.1) - declares none, and a producer is described by the class that declares
     * it: one a superclass declares is the superclass's bean (section 4.2).</p>
     */
    void register(ClassElement element, Messages messages, VisitorContext context) {
        if (registrars.isEmpty() || element.isAbstract() || element.isInterface()
            || io.micronaut.cdi.processor.visitor.SpecificationBeans.isVetoed(element)) {
            return;
        }
        if (!registered.add(element.getName())) {
            return;
        }
        List<ElementBeanInfo> beans = new ArrayList<>();
        if (io.micronaut.cdi.processor.visitor.SpecificationBeans.isClassBean(element)) {
            beans.add(ElementBeanInfo.of(element, null, context));
        }
        for (io.micronaut.inject.ast.MemberElement producer
            : io.micronaut.cdi.processor.visitor.SpecificationBeans.producersOf(element)) {
            beans.add(ElementBeanInfo.of(element, producer, context));
        }
        for (ElementBeanInfo bean : beans) {
            for (Registrar registrar : registrars) {
                if (registrar.matches(bean)) {
                    registrar.describe(bean, messages, context);
                }
            }
        }
        // section 2.10.3 also tells the phase about the observers: a registration method asking for an
        // ObserverInfo is invoked once for each observer whose observed event type matches. An observer method
        // is inherited, so the bean of this class observes with the ones its superclasses declare as well
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
            ElementObserverInfo observer = new ElementObserverInfo(element, method, observed, async, context);
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
            && !element.getAnnotationMetadata().hasStereotype(CdiScope.class)) {
            // added to the scanned classes during discovery: a bean as though it declared the dependent scope,
            // recorded as one so that it is a bean of the specification - described to the registration phase and
            // validated as any. Written only where nothing gives the class a scope: a stereotype's must win
            for (AnnotationValue<?> annotation : io.micronaut.cdi.processor.CdiScopes.annotationsOf(
                io.micronaut.cdi.processor.Cdi.DEPENDENT, false)) {
                element.annotate(annotation);
            }
        }
        for (AnnotationValue<?> annotation : discovered.annotationsFor(element.getName())) {
            element.annotate(annotation);
        }
        java.util.Map<String, List<AnnotationValue<?>>> members =
            discovered.memberAnnotationsFor(element.getName());
        if (!members.isEmpty()) {
            // the members of an annotation interface as the compiler answers them, which KSP does as properties
            NonbindingMembersRemapper.memberElementsOf(element).forEach((name, member) -> {
                for (AnnotationValue<?> annotation : members.getOrDefault(name, List.of())) {
                    member.annotate(annotation);
                }
            });
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
            return asksForObservers() && matches(observer.observedType());
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
            ElementClassConfig classConfig = new ElementClassConfig(element, context);
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
     * The extensions a harness handed in for one deployment, and where it wants to hear what their discovery
     * phase added to the scanned classes.
     *
     * @param extensions     The extensions
     * @param scannedClasses Handed the classes the discovery phase added
     */
    private record Deployment(List<BuildCompatibleExtension> extensions,
                              java.util.function.Consumer<java.util.Set<String>> scannedClasses) {
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
