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

import io.micronaut.cdi.internal.metadata.CdiScannedClasses;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.visitor.BeanDiscoveryVisitor;
import io.micronaut.cdi.processor.visitor.BeanNameVisitor;
import io.micronaut.cdi.processor.visitor.BeanTypesVisitor;
import io.micronaut.cdi.processor.visitor.CdiScopeVisitor;
import io.micronaut.cdi.processor.visitor.ContainerInjectionVisitor;
import io.micronaut.cdi.processor.visitor.DefaultQualifierVisitor;
import io.micronaut.cdi.processor.visitor.InjectionPointRulesVisitor;
import io.micronaut.cdi.processor.visitor.ObserverVisitor;
import io.micronaut.cdi.processor.visitor.ProducerVisitor;
import io.micronaut.cdi.processor.visitor.ProxyabilityVisitor;
import io.micronaut.cdi.processor.visitor.RecordedTypeValues;
import io.micronaut.cdi.processor.visitor.SpecificationBeans;
import io.micronaut.cdi.processor.visitor.TypeHierarchyVisitor;
import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.ast.beans.BeanMethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sees to the classes the discovery phase added to the scanned ones (section 2.10.1), once the first round of a
 * compilation that visited any class is finished: under javac they are imported, so that javac makes beans of them
 * in the next round, and under KSP and Groovy a bean is registered for each class of a library that has no
 * definition, put through what a class of the compilation is put through as it is visited.
 *
 * <p>It is made for one round's finish, from what {@link BuildCompatibleExtensionVisitor} discovered, and keeps
 * nothing beyond it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class ScannedClassRegistrar {

    /**
     * The package of the classes the visitor generates.
     */
    static final String GENERATED = "io.micronaut.cdi.generated";
    private static final String SCANNED_CLASSES = "ScannedClassesImport";
    private static final int MAX_COMPILATIONS_OF_A_MODULE = 16;

    private final BuildCompatibleExtensionVisitor visitor;
    private final DiscoveredClasses discovered;

    /**
     * @param visitor    The visitor, which puts a class through the discovery, enhancement and registration phases
     * @param discovered What the discovery phase said and which classes the compiler visited
     */
    ScannedClassRegistrar(BuildCompatibleExtensionVisitor visitor, DiscoveredClasses discovered) {
        this.visitor = visitor;
        this.discovered = discovered;
    }

    /**
     * Whether the class is one the visitor generated: the import of the scanned classes.
     *
     * @param className The name of the class
     * @return Whether it was generated
     */
    static boolean isGenerated(String className) {
        return className.startsWith(GENERATED + ".");
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
    boolean importScannedClasses(VisitorContext context) {
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
        StringBuilder source = new StringBuilder("package " + GENERATED + ";\n\n");
        if (context.getClassElement(CdiScannedClasses.class.getName()).isPresent()) {
            // the record of what was scanned, for whoever reads the compiled classes: the import itself says
            // nothing once compiled
            List<String> names = new ArrayList<>();
            for (String scanned : discovered.scannedClasses()) {
                names.add('"' + scanned + '"');
            }
            source.append("@" + CdiScannedClasses.class.getName()
                + "({" + String.join(", ", names) + "})\n");
        }
        source.append("@io.micronaut.context.annotation.ClassImport(classes = {" + String.join(", ", literals) + "})\n")
            .append("final class " + className + " {\n}\n");
        // written with no originating element: the visitor stays isolating, because only the isolating processor
        // runs it before the other visitors, as an enhancement has to be, and Gradle then recompiles the whole
        // source set rather than part of it, which is what the source needs
        Optional<GeneratedFile> file = context.visitGeneratedSourceFile(GENERATED, className);
        if (file.isEmpty()) {
            context.fail("The import of the scanned classes could not be written: the compilation accepts no "
                + "generated source", null);
            return false;
        }
        try {
            file.get().write(writer -> writer.write(source.toString()));
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
    void registerScannedClasses(VisitorContext context) {
        Messages messages = new VisitorMessages(context);
        for (String scanned : discovered.scannedClasses()) {
            int nested = scanned.indexOf('$');
            if (discovered.wasVisited(scanned) || (nested > 0 && discovered.wasVisited(scanned.substring(0, nested)))) {
                continue;
            }
            ClassElement element = context.getClassElement(scanned).orElse(null);
            if (element == null || element.isAbstract() || element.isInterface() || hasDefinition(element, context)) {
                continue;
            }
            visitor.prepareScanned(element, context);
            // what each class of the compilation is put through as it is visited: its scope, its injection points
            // marked for the container, its bean types, its producers and observers, its default qualifier
            for (TypeElementVisitor<?, ?> visitor : classVisitors()) {
                if (!(visitor instanceof InjectionPointRulesVisitor)) {
                    // the rules a class is held to as it compiles are its own compilation's to judge; and a compiler
                    // that reads a class of a library from its bytes reads less of it than of a source
                    visitor.visitClass(element, context);
                }
            }
            visitor.register(element, messages, context);
            Map<String, MemberElement> producers = new HashMap<>();
            for (MemberElement producer
                : SpecificationBeans.producersOf(element)) {
                producers.put(producerKey(producer), producer);
            }
            ElementQuery<MethodElement> executable = ElementQuery.ALL_METHODS
                .annotated(metadata -> metadata.hasStereotype(Executable.class));
            context.registerBean(element, visitor.contributors()).inject()
                // the observers, disposers and invoked methods the visitors marked executable: the builder writes the
                // executable methods it is handed, an instance or a static one, and finds none of its own
                .withMethods(executable, BeanMethodElement::executable)
                .withMethods(executable.onlyStatic(), BeanMethodElement::executable)
                .produceBeans(ElementQuery.ALL_METHODS.onlyDeclared()
                    .annotated(metadata -> metadata.hasDeclaredAnnotation(Cdi.PRODUCES)),
                    produced -> describedAsVisited(produced, producers))
                .produceBeans(ElementQuery.ALL_FIELDS.onlyDeclared()
                    .annotated(metadata -> metadata.hasDeclaredAnnotation(Cdi.PRODUCES)),
                    produced -> describedAsVisited(produced, producers));
        }
    }

    /**
     * Gives the bean a producer of a scanned class of a library produces what the visitors recorded of the producer:
     * the builder reads the member anew, without what they wrote on it.
     */
    private static void describedAsVisited(BeanElementBuilder produced,
                                           Map<String, MemberElement> producers) {
        if (!(produced.getProducingElement() instanceof MemberElement member)) {
            return;
        }
        MemberElement visited = producers.get(producerKey(member));
        if (visited == null) {
            return;
        }
        AnnotationMetadata recorded = visited.getAnnotationMetadata().getDeclaredMetadata();
        for (String name : recorded.getAnnotationNames()) {
            AnnotationValue<Annotation> value = recorded.getAnnotation(name);
            if (value != null && !produced.hasDeclaredAnnotation(name)) {
                produced.annotate(value);
            }
        }
    }

    private static String producerKey(MemberElement member) {
        if (member instanceof MethodElement method) {
            StringBuilder key = new StringBuilder(method.getName()).append('(');
            for (ParameterElement parameter : method.getParameters()) {
                // with its dimensions, so that produce(Foo) and produce(Foo[]) are two producers
                key.append(RecordedTypeValues.erasedName(parameter.getType())).append(',');
            }
            return key.append(')').toString();
        }
        return member.getName();
    }

    /**
     * The visitors of this module that read one class at a time, in the order Micronaut runs them in.
     */
    private static List<TypeElementVisitor<?, ?>> classVisitors() {
        List<TypeElementVisitor<?, ?>> visitors = new ArrayList<>(List.of(
            new CdiScopeVisitor(),
            new ContainerInjectionVisitor(),
            new BeanNameVisitor(),
            new BeanDiscoveryVisitor(),
            new ProducerVisitor(),
            new ObserverVisitor(),
            new DefaultQualifierVisitor(),
            new InjectionPointRulesVisitor(),
            new ProxyabilityVisitor(),
            new BeanTypesVisitor(),
            new TypeHierarchyVisitor()));
        OrderUtil.reverseSort(visitors);
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
     * What the class this compilation generates is named with: the import of the scanned classes, which is generated
     * once.
     */
    private String suffix(VisitorContext context) {
        return suffixOf(discovered.contributors().get(0).getName(), context);
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
}
