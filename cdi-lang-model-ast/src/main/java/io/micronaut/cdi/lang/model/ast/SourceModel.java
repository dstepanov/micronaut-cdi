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
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The language model read from Micronaut's AST alone, in whichever language the compilation is in: what it reads of a
 * declaration beyond what one Micronaut element answers on its own.
 *
 * <p>One question the AST cannot answer completely is the annotations of a declaration exactly as the source wrote
 * them: a repeatable annotation written beside an explicit container is folded into that container. This remaining
 * limitation is described under "Open points in Micronaut Core" in {@code CONFORMANCE.md}. The annotation on a
 * primitive type use, and the targets, the container and the retention of an annotation interface, are answered by
 * Micronaut Core 5.3.</p>
 *
 * <p>The annotations of a declaration are the ones Micronaut recorded, as far as the registered
 * {@link LanguageModelAnnotationFilter}s allow, read the way the specification's model reads: a repeatable
 * annotation Micronaut folded into its container although it was written once is reported as itself, and an
 * annotation interface reports the {@code Retention} it declares. Array dimensions retain their own type-use
 * annotations through Micronaut Core 5.3.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SourceModel {

    private static final String OBJECT = "java.lang.Object";
    private static final String RETENTION = "java.lang.annotation.Retention";
    private static final String REPEATABLE = "java.lang.annotation.Repeatable";

    private final VisitorContext context;

    private SourceModel(VisitorContext context) {
        this.context = context;
    }

    /**
     * The model of a compilation, which reads what an element does not answer on its own - the interface an
     * annotation names, its retention and its targets - through the compiler.
     *
     * @param context The compilation
     * @return The model
     */
    static SourceModel of(VisitorContext context) {
        return new SourceModel(context);
    }

    /**
     * The annotations written on a declaration, the ones an extension added included and the ones it removed
     * left out, retained until runtime only.
     *
     * @param element The declaration
     * @return The annotations
     */
    public List<AnnotationInfo> annotationsOf(Element element) {
        List<AnnotationInfo> found = new ArrayList<>();
        for (String name : element.getDeclaredAnnotationNames()) {
            if (!ExtensionAnnotations.isReported(element, name, context)) {
                continue;
            }
            AnnotationValue<Annotation> annotation = element.getDeclaredAnnotation(name);
            if (annotation != null) {
                found.add(new ElementAnnotationInfo(annotation, context));
            }
        }
        if (element instanceof AnnotationElement annotation) {
            addMetaAnnotations(annotation, found, context);
        }
        return ExtensionAnnotations.unfoldSingleRepetitions(found, element);
    }

    /**
     * The meta-annotations an annotation interface declares, which Micronaut leaves out of its record as the
     * ones it reads itself, put back from what it answers about the interface: its {@code Retention} — where
     * {@code CLASS} is what an interface that declares nothing has, and is left out; Micronaut answers
     * {@code RUNTIME} for an interface that declares nothing, so such an interface reports a {@code Retention}
     * it did not write — and the container of a repeatable one, which is only ever declared. Its
     * {@code Target} is not put back, since the targets of an interface that declares none read the same as
     * declared ones.
     */
    private static void addMetaAnnotations(AnnotationElement annotation, List<AnnotationInfo> found,
                                           VisitorContext context) {
        Set<String> present = new HashSet<>();
        found.forEach(each -> present.add(each.name()));
        RetentionPolicy retention = annotation.getRetentionPolicy();
        if (!present.contains(RETENTION) && retention != RetentionPolicy.CLASS
            && ExtensionAnnotations.isReported(annotation, RETENTION, context)) {
            found.add(new ElementAnnotationInfo(
                AnnotationValue.builder(RETENTION).member("value", retention.name()).build(), context));
        }
        String container = annotation.getRepeatableContainer().orElse(null);
        if (container != null && !present.contains(REPEATABLE)
            && ExtensionAnnotations.isReported(annotation, REPEATABLE, context)) {
            found.add(new ElementAnnotationInfo(
                AnnotationValue.builder(REPEATABLE).member("value", new AnnotationClassValue<>(container)).build(),
                context));
        }
    }

    /**
     * The repetitions of a repeatable annotation on a declaration, written one by one or inside the container.
     *
     * @param element    The declaration
     * @param annotation The annotation interface's binary name
     * @return The repetitions, in the order written
     */
    public List<AnnotationInfo> repeatableOn(Element element, String annotation) {
        // Micronaut resolves the container of a repeatable annotation itself when asked for the annotations of
        // one name, whether they were written one by one or inside the container
        if (!ExtensionAnnotations.isReported(element, annotation, context)) {
            return List.of();
        }
        List<AnnotationInfo> found = new ArrayList<>();
        for (AnnotationValue<Annotation> value : element.getDeclaredAnnotationValuesByName(annotation)) {
            found.add(new ElementAnnotationInfo(value, context));
        }
        return found;
    }

    /**
     * The type of a field, as declared.
     *
     * @param field The field
     * @return The type
     */
    public Type typeOf(FieldElement field) {
        return ElementTypes.of(field.getType(), field.getDeclaringType(), context);
    }

    /**
     * The type of a parameter, as declared.
     *
     * @param parameter The parameter
     * @return The type
     */
    public Type typeOf(ParameterElement parameter) {
        return ElementTypes.of(parameter.getType(), parameter.getMethodElement().getDeclaringType(), context);
    }

    /**
     * What a method returns, with the method's own type variables.
     *
     * @param method The method, not a constructor
     * @return The type
     */
    public Type returnTypeOf(MethodElement method) {
        return ElementTypes.of(method.getReturnType(), method.getDeclaringType(), context);
    }

    /**
     * The class a constructor constructs, carrying the annotations written before the constructor's name that
     * may be written on a type use.
     *
     * @param constructor The constructor
     * @return The type
     */
    public ClassType constructorReturnTypeOf(MethodElement constructor) {
        // the annotations of the use are the ones written on the constructor that may be written on a type
        List<AnnotationInfo> annotations = new ArrayList<>();
        for (AnnotationInfo annotation : annotationsOf(constructor)) {
            if (isTypeUse(annotation.name())) {
                annotations.add(annotation);
            }
        }
        return ElementTypes.rawClassOf(constructor.getDeclaringType(), annotations, context);
    }

    /**
     * The receiver of a method: the declaring type for an instance method and for the constructor of an inner
     * class, carrying whatever was written on a receiver parameter; nothing for a static method or the
     * constructor of a class that is not inner.
     *
     * @param method The method
     * @return The receiver type, or {@code null}
     */
    public @Nullable Type receiverTypeOf(MethodElement method) {
        if (method.isStatic()) {
            return null;
        }
        ClassElement declaring = method.getDeclaringType();
        // isInner() answers for every nested class, a static one included, which has no receiver
        if (method instanceof ConstructorElement && !(declaring.isInner() && !declaring.isStatic())) {
            return null;
        }
        return method.getReceiverType().map(type -> ElementTypes.of(type, context))
            .orElseGet(() -> ElementTypes.of(declaring, context));
    }

    /**
     * The types a method declares it throws.
     *
     * @param method The method
     * @return The types
     */
    public List<Type> throwsTypesOf(MethodElement method) {
        ClassElement[] thrown = method.getThrownTypes();
        List<Type> types = new ArrayList<>(thrown.length);
        for (ClassElement type : thrown) {
            types.add(ElementTypes.of(type, context));
        }
        return types;
    }

    /**
     * The type variables a class declares.
     *
     * @param clazz The class
     * @return The variables
     */
    public List<TypeVariable> typeParametersOf(ClassElement clazz) {
        return variablesOf(clazz.getDeclaredGenericPlaceholders());
    }

    /**
     * The type variables a method declares.
     *
     * @param method The method
     * @return The variables
     */
    public List<TypeVariable> typeParametersOf(MethodElement method) {
        return variablesOf(method.getDeclaredTypeVariables());
    }

    private List<TypeVariable> variablesOf(List<? extends GenericPlaceholderElement> placeholders) {
        List<TypeVariable> variables = new ArrayList<>(placeholders.size());
        for (GenericPlaceholderElement placeholder : placeholders) {
            variables.add(ElementTypes.variableOf(placeholder, context));
        }
        return variables;
    }

    /**
     * The superclass of a class as its {@code extends} clause wrote it; {@code java.lang.Object} when it wrote
     * none.
     *
     * @param clazz The class
     * @return The type, or {@code null} for an interface and for {@code java.lang.Object}
     */
    public @Nullable Type superClassOf(ClassElement clazz) {
        if (clazz.isInterface() || OBJECT.equals(clazz.getName())) {
            return null;
        }
        // a class whose superclass is Object still has one, though Micronaut's model leaves it implicit
        return clazz.getSuperType().map(type -> ElementTypes.of(type, context))
            .orElseGet(() -> ElementTypes.objectType(context));
    }

    /**
     * The interfaces of a class as its {@code implements} clause wrote them.
     *
     * @param clazz The class
     * @return The types
     */
    public List<Type> superInterfacesOf(ClassElement clazz) {
        List<Type> types = new ArrayList<>();
        for (ClassElement type : clazz.getInterfaces()) {
            types.add(ElementTypes.of(type, context));
        }
        return types;
    }

    /**
     * The annotation interface holding the repetitions of a repeatable annotation.
     *
     * @param annotation The annotation interface's binary name
     * @return The container's binary name, or {@code null} when it is not repeatable or cannot be told
     */
    public @Nullable String containerOf(String annotation) {
        return ExtensionAnnotationTypes.declarationOf(annotation, context) instanceof AnnotationElement declaration
            ? declaration.getRepeatableContainer().orElse(null) : null;
    }

    /**
     * Whether an annotation interface may be written on a use of a type.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether {@code TYPE_USE} is among its targets; {@code false} when it cannot be told
     */
    public boolean isTypeUse(String annotation) {
        return ExtensionAnnotationTypes.declarationOf(annotation, context) instanceof AnnotationElement declaration
            && declaration.getTargets().contains(ElementType.TYPE_USE);
    }
}
