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
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.CdiScopes;
import io.micronaut.cdi.processor.mapper.NonbindingMembers;
import io.micronaut.cdi.processor.visitor.SpecificationBeans;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.cdi.internal.metadata.CdiScope;
import jakarta.enterprise.inject.build.compatible.spi.BuildServicesResolver;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * extension therefore goes on the annotation processor path beside this module. A build may name the extensions
 * of a compilation instead, with the compiler options {@link #EXTENSIONS_OPTION} and
 * {@link #EXTENSIONS_ONLY_OPTION}, which is how a harness that compiles a deployment at a time hands each
 * compilation the extensions of its deployment.</p>
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
     * The compiler option that names the build compatible extensions the compilation runs, as a comma-separated
     * list of class names, beside the ones the service loader finds: {@code -Amicronaut.cdi.extensions=a.B,c.D}.
     * Each named class is instantiated through its constructor without parameters, as the specification has an
     * extension be.
     */
    public static final String EXTENSIONS_OPTION = "micronaut.cdi.extensions";

    /**
     * The compiler option that, when {@code true}, has the compilation run the extensions named by
     * {@link #EXTENSIONS_OPTION} and no others, with no service loading: what a harness that compiles a deployment
     * at a time sets, so that the extensions of one deployment are the only ones to have their say.
     */
    public static final String EXTENSIONS_ONLY_OPTION = "micronaut.cdi.extensions.only";

    /**
     * The extensions of the compilation and the methods of their phases, loaded as the compilation starts the
     * visitor: none for a visitor a compiler constructs and never starts.
     */
    private ExtensionMethods extensions = ExtensionMethods.none();
    /**
     * What the discovery phase said and which classes the compiler visited: the one thing this visitor keeps from
     * one class to the next, made as the compilation starts the visitor and read as its rounds finish. A visitor a
     * compiler constructs and never starts has none.
     */
    private @Nullable DiscoveredClasses discovered;
    /**
     * Which of the phases that wait for the whole deployment have run, which run once: the scanned classes have
     * been seen to - imported, under javac, or registered - and the synthesis and validation phases have run.
     */
    private boolean scannedClassesHandled;
    private boolean synthesized;

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(EXTENSIONS_OPTION, EXTENSIONS_ONLY_OPTION);
    }

    @Override
    public TypeElementQuery query() {
        return TypeElementQuery.onlyClass();
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
    Element[] contributors() {
        return discovered().contributors().toArray(new Element[0]);
    }

    /**
     * What the discovery phase said and which classes the compiler visited, which the compilation made as it
     * started this visitor.
     */
    private DiscoveredClasses discovered() {
        DiscoveredClasses started = discovered;
        if (started == null) {
            throw new IllegalStateException("The build compatible extension visitor was not started");
        }
        return started;
    }

    @Override
    public void start(VisitorContext context) {
        // what AnnotationBuilder.of composes with, reading the annotation interfaces through this compilation. The
        // specification's resolver is the static entry point of its build time API, which an extension calls with
        // nothing to hand it; it is set here, as the compilation starts, rather than left to the service loader,
        // which looks through the loader of the API and does not always see the service entry of this module
        BuildServicesResolver.setBuildServices(
            new ElementBuildServices(context));
        // the visitor the compilation started is the one that visits its classes: a compiler may construct
        // others that it never starts. The discovery phase runs once, as it is first started
        DiscoveredClasses started = discovered;
        if (started == null) {
            ExtensionMethods loaded = ExtensionMethods.load(context);
            extensions = loaded;
            started = new DiscoveredClasses();
            discovered = started;
            started.compilation(context);
            loaded.discover(started, context);
        }
        // what the discovery phase said about annotations is put on the annotation types before any class is
        // visited: a class's metadata folds its annotations' metadata in as it is built, and a qualifier or
        // binding registered by an extension has to be one by then. What it marked on the members of an
        // annotation interface is read from there by Micronaut as it reads each occurrence
        for (String describedClass : started.describedClassNames()) {
            context.getClassElement(describedClass).ifPresent(this::applyDiscovery);
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String name = element.getName();
        DiscoveredClasses started = discovered;
        if (started == null || ScannedClassRegistrar.isGenerated(name)) {
            // a class this visitor generated: the import of the scanned classes
            return;
        }
        started.visited(element);
        applyDiscovery(element);
        extensions.enhance(element, context);
    }

    /**
     * Runs the registration phase of section 2.10.3 for each class the round visited, and then what waits for every
     * class of the compilation to have been registered, as the compiler finishes the first round that visited any:
     * the registration of the scanned classes the compiler does not make beans of, of the context classes and of the
     * registered qualifiers, and the synthesis of section 2.10.5, the registration of what it described, and the
     * validation of section 2.10.6.
     *
     * <p>A round is finished once every visitor has visited every class of it, and before the beans of the round
     * are written, in javac, KSP and Groovy alike: what the registration phase is told about a class is the bean as
     * everything else - the enhancements included - has left it, and what is registered here is written with the
     * beans of the round, and needs no source. Under javac, where the scanned classes are imported rather than
     * registered, the phases that follow wait for the round that visits them. They run once: a later round finds
     * them run.</p>
     */
    @Override
    public void finishRound(VisitorContext context) {
        DiscoveredClasses started = discovered;
        if (started == null) {
            return;
        }
        Messages messages = new VisitorMessages(context);
        for (ClassElement element : started.takeUnregistered()) {
            register(element, messages, context);
        }
        if (started.contributors().isEmpty()) {
            return;
        }
        started.roundFinished();
        finishThisRound(context);
    }

    private void finishThisRound(VisitorContext context) {
        if (synthesized) {
            return;
        }
        if (!scannedClassesHandled) {
            scannedClassesHandled = true;
            ScannedClassRegistrar scanned = new ScannedClassRegistrar(this, discovered());
            if (scanned.importScannedClasses(context)) {
                // javac visits the imported classes in the next round, describes them to the registration phase,
                // and finishes that round with the phases that follow
                return;
            }
            scanned.registerScannedClasses(context);
        }
        synthesized = true;
        registerContexts(context);
        registerQualifiers(context);
        synthesise(context);
    }

    /**
     * Declares a bean for every context class an extension registered (section 2.10.1), recording the scope
     * it serves: the container obtains the context from the bean as it starts.
     */
    private void registerContexts(VisitorContext context) {
        discovered().contexts().forEach((scopeName, contextClasses) -> {
            for (String contextClass : contextClasses) {
                try {
                    registerComponent(context, new SynthesisPhase.Component(
                        SyntheticRecords.classElement(context, contextClass, "context"),
                        AnnotationValue.builder(CdiRegisteredContext.class)
                            .member("scope", new AnnotationClassValue<>(scopeName))
                            .member("normal", discovered().isNormalContext(scopeName))
                            .build()));
                } catch (IllegalArgumentException e) {
                    context.fail(String.valueOf(e.getMessage()), discovered().contributors().get(0));
                }
            }
        });
    }

    /**
     * Records the annotations the extensions made qualifiers of, on a bean registered for the purpose: no bean
     * may use such an annotation, so the record is the one witness of it the container has.
     */
    private void registerQualifiers(VisitorContext context) {
        if (discovered().registeredQualifiers().isEmpty()) {
            return;
        }
        ClassElement record = context.getClassElement(CdiDeploymentRecord.class.getName()).orElse(null);
        if (record == null) {
            context.fail("The qualifiers the build compatible extensions registered cannot be recorded: "
                + CdiDeploymentRecord.class.getName() + ", of micronaut-cdi, is not on the classpath of the "
                + "compilation", discovered().contributors().get(0));
            return;
        }
        context.registerBean(record, contributors())
            .annotate(AnnotationValue.builder(CdiExtensionComponents.class).build())
            .annotate(AnnotationValue.builder(CdiExtensionQualifiers.class)
                .values(discovered().registeredQualifiers().toArray(new String[0]))
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
     * Fails the compilation where the phases that follow registration never ran: nothing is left out
     * silently. The Groovy and Kotlin compilers finish once, when nothing more is compiled.
     */
    @Override
    public void finish(VisitorContext context) {
        // a Java compilation finishes every round
        DiscoveredClasses started = discovered;
        if (context.getLanguage() == VisitorContext.Language.JAVA || started == null) {
            return;
        }
        if (!synthesized && !started.contributors().isEmpty()) {
            context.fail("The synthesis and "
                + "validation phases of the build compatible extensions did not run: the compiler finished no "
                + "round of the compilation", null);
        }
    }

    /**
     * Runs the phases that follow the registration of the compiled beans, once every class of the compilation
     * has been visited: the synthesis of section 2.10.5, the registration of what it described, and the
     * validation of section 2.10.6.
     */
    private void synthesise(VisitorContext context) {
        if (!extensions.hasLatePhases()) {
            return;
        }
        // what an extension reports from here on is about the deployment as a whole rather than about a
        // definition the compiler is looking at
        Messages messages = VisitorMessages.ofTheDeployment(context);
        SynthesisPhase synthesis = new SynthesisPhase(context, discovered());
        if (!extensions.synthesize(synthesis, messages, context)) {
            return;
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
            extensions.describeSyntheticBean(info, info.beanType(), messages, context);
        }
        for (RecordingObserverBuilder<?> observer : synthesis.observers()) {
            SyntheticObserverInfo info = new SyntheticObserverInfo(observer, context);
            extensions.describeSyntheticObserver(info, ElementTypes.elementOf(observer.eventType()), messages,
                context);
        }
        // the built-in beans are beans of the application too (section 2.10.3): no class of the compilation
        // declares them, so the phase is told about them here
        context.getClassElement("io.micronaut.cdi.internal.runtime.CdiBeanContainer").ifPresent(container -> {
            extensions.describeBean(ElementBeanInfo.of(container, null, context), messages, context);
        });
        // and the validation phase is the last word on it
        extensions.validate(messages, context);
    }

    /**
     * Describes to the extensions each bean this class declares: the class itself where it is a bean, and every
     * producer it declares.
     *
     * <p>The phase is invoked once per bean rather than once for the container, which is what the specification
     * says it is and what a compiler can give. It runs as the round finishes, so that what it describes is the bean as everything
     * else has left it. A class that cannot be a bean - one vetoed, an abstract class, an interface, one with no
     * bean constructor (section 3.1.1) - declares none, and a producer is described by the class that declares
     * it: one a superclass declares is the superclass's bean (section 4.2).</p>
     */
    void register(ClassElement element, Messages messages, VisitorContext context) {
        if (!extensions.registers() || element.isAbstract() || element.isInterface()
            || SpecificationBeans.isVetoed(element) || !SpecificationBeans.hasABeanConstructor(element)) {
            return;
        }
        if (!discovered().registering(element.getName())) {
            return;
        }
        List<ElementBeanInfo> beans = new ArrayList<>();
        if (SpecificationBeans.isClassBean(element)) {
            beans.add(ElementBeanInfo.of(element, null, context));
        }
        for (MemberElement producer
            : SpecificationBeans.producersOf(element)) {
            beans.add(ElementBeanInfo.of(element, producer, context));
        }
        for (ElementBeanInfo bean : beans) {
            extensions.describeBean(bean, messages, context);
        }
        // section 2.10.3 also tells the phase about the observers: a registration method asking for an
        // ObserverInfo is invoked once for each observer whose observed event type matches. An observer method
        // is inherited, so the bean of this class observes with the ones its superclasses declare as well
        for (MethodElement method
            : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            ParameterElement observed = null;
            boolean async = false;
            for (ParameterElement parameter : method.getParameters()) {
                if (parameter.hasDeclaredAnnotation(Cdi.OBSERVES)) {
                    observed = parameter;
                } else if (parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)) {
                    observed = parameter;
                    async = true;
                }
            }
            if (observed == null) {
                continue;
            }
            extensions.describeObserver(new ElementObserverInfo(element, method, observed, async, context), messages,
                context);
        }
    }

    /**
     * Puts a scanned class of a library the compiler does not visit through what visiting a class does here: what
     * the discovery phase said about it, and the enhancement phase.
     *
     * @param element The class
     * @param context The compilation
     */
    void prepareScanned(ClassElement element, VisitorContext context) {
        applyDiscovery(element);
        extensions.enhance(element, context);
    }

    /**
     * Puts on the class what the discovery phase said about it: the annotation that makes it a qualifier, an
     * interceptor binding or a stereotype, and the scope that makes it a bean where it was added to the
     * scanned classes.
     */
    private void applyDiscovery(ClassElement element) {
        if (discovered().isEmpty()) {
            return;
        }
        if (discovered().isScanned(element.getName())
            && !element.getAnnotationMetadata().hasStereotype("jakarta.inject.Scope")
            && !element.getAnnotationMetadata().hasStereotype(CdiScope.class)) {
            // added to the scanned classes during discovery: a bean as though it declared the dependent scope,
            // recorded as one so that it is a bean of the specification - described to the registration phase and
            // validated as any. Written only where nothing gives the class a scope: a stereotype's must win
            for (AnnotationValue<?> annotation : CdiScopes.annotationsOf(
                Cdi.DEPENDENT, false)) {
                element.annotate(annotation);
            }
        }
        for (AnnotationValue<?> annotation : discovered().annotationsFor(element.getName())) {
            element.annotate(annotation);
        }
        Map<String, List<AnnotationValue<?>>> members =
            discovered().memberAnnotationsFor(element.getName());
        if (!members.isEmpty()) {
            // the members of an annotation interface, as Micronaut answers them under every compiler: a member
            // marked Nonbinding here is read by Micronaut from the compilation's view of the member as it records
            // the non-binding members on every occurrence read afterwards
            NonbindingMembers.memberElementsOf(element).forEach((name, member) -> {
                for (AnnotationValue<?> annotation : members.getOrDefault(name, List.of())) {
                    member.annotate(annotation);
                }
            });
        }
    }

}
