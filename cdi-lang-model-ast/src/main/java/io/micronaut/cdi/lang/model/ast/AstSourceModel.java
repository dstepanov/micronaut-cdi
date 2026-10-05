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
 * The language model read from Micronaut's AST alone, in whichever language the compilation is in.
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
final class AstSourceModel implements SourceModel {

    static final AstSourceModel INSTANCE = new AstSourceModel();

    private static final String OBJECT = "java.lang.Object";
    private static final String RETENTION = "java.lang.annotation.Retention";
    private static final String REPEATABLE = "java.lang.annotation.Repeatable";

    private AstSourceModel() {
    }

    @Override
    public List<AnnotationInfo> annotationsOf(Element element) {
        List<AnnotationInfo> found = new ArrayList<>();
        for (String name : element.getDeclaredAnnotationNames()) {
            if (!ExtensionAnnotations.isReported(element, name)) {
                continue;
            }
            AnnotationValue<Annotation> annotation = element.getDeclaredAnnotation(name);
            if (annotation != null) {
                found.add(new ElementAnnotationInfo(annotation));
            }
        }
        if (element instanceof AnnotationElement annotation) {
            addMetaAnnotations(annotation, found);
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
    private static void addMetaAnnotations(AnnotationElement annotation, List<AnnotationInfo> found) {
        Set<String> present = new HashSet<>();
        found.forEach(each -> present.add(each.name()));
        RetentionPolicy retention = annotation.getRetentionPolicy();
        if (!present.contains(RETENTION) && retention != RetentionPolicy.CLASS
            && ExtensionAnnotations.isReported(annotation, RETENTION)) {
            found.add(new ElementAnnotationInfo(
                AnnotationValue.builder(RETENTION).member("value", retention.name()).build()));
        }
        String container = annotation.getRepeatableContainer().orElse(null);
        if (container != null && !present.contains(REPEATABLE)
            && ExtensionAnnotations.isReported(annotation, REPEATABLE)) {
            found.add(new ElementAnnotationInfo(
                AnnotationValue.builder(REPEATABLE).member("value", new AnnotationClassValue<>(container)).build()));
        }
    }

    @Override
    public List<AnnotationInfo> repeatableOn(Element element, String annotation) {
        // Micronaut resolves the container of a repeatable annotation itself when asked for the annotations of
        // one name, whether they were written one by one or inside the container
        if (!ExtensionAnnotations.isReported(element, annotation)) {
            return List.of();
        }
        List<AnnotationInfo> found = new ArrayList<>();
        for (AnnotationValue<Annotation> value : element.getDeclaredAnnotationValuesByName(annotation)) {
            found.add(new ElementAnnotationInfo(value));
        }
        return found;
    }

    @Override
    public Type typeOf(FieldElement field) {
        return ElementTypes.of(field.getType(), field.getDeclaringType());
    }

    @Override
    public Type typeOf(ParameterElement parameter) {
        return ElementTypes.of(parameter.getType(), parameter.getMethodElement().getDeclaringType());
    }

    @Override
    public Type returnTypeOf(MethodElement method) {
        return ElementTypes.of(method.getReturnType(), method.getDeclaringType());
    }

    @Override
    public ClassType constructorReturnTypeOf(MethodElement constructor) {
        // the annotations of the use are the ones written on the constructor that may be written on a type
        List<AnnotationInfo> annotations = new ArrayList<>();
        for (AnnotationInfo annotation : annotationsOf(constructor)) {
            if (isTypeUse(annotation.name())) {
                annotations.add(annotation);
            }
        }
        return ElementTypes.rawClassOf(constructor.getDeclaringType(), annotations);
    }

    @Override
    public @Nullable Type receiverTypeOf(MethodElement method) {
        if (method.isStatic()) {
            return null;
        }
        ClassElement declaring = method.getDeclaringType();
        // isInner() answers for every nested class, a static one included, which has no receiver
        if (method instanceof ConstructorElement && !(declaring.isInner() && !declaring.isStatic())) {
            return null;
        }
        return method.getReceiverType().map(ElementTypes::of).orElseGet(() -> ElementTypes.of(declaring));
    }

    @Override
    public List<Type> throwsTypesOf(MethodElement method) {
        ClassElement[] thrown = method.getThrownTypes();
        List<Type> types = new ArrayList<>(thrown.length);
        for (ClassElement type : thrown) {
            types.add(ElementTypes.of(type));
        }
        return types;
    }

    @Override
    public List<TypeVariable> typeParametersOf(ClassElement clazz) {
        return variablesOf(clazz.getDeclaredGenericPlaceholders());
    }

    @Override
    public List<TypeVariable> typeParametersOf(MethodElement method) {
        return variablesOf(method.getDeclaredTypeVariables());
    }

    private static List<TypeVariable> variablesOf(List<? extends GenericPlaceholderElement> placeholders) {
        List<TypeVariable> variables = new ArrayList<>(placeholders.size());
        for (GenericPlaceholderElement placeholder : placeholders) {
            variables.add(ElementTypes.variableOf(placeholder));
        }
        return variables;
    }

    @Override
    public @Nullable Type superClassOf(ClassElement clazz) {
        if (clazz.isInterface() || OBJECT.equals(clazz.getName())) {
            return null;
        }
        // a class whose superclass is Object still has one, though Micronaut's model leaves it implicit
        return clazz.getSuperType().map(ElementTypes::of).orElseGet(ElementTypes::objectType);
    }

    @Override
    public List<Type> superInterfacesOf(ClassElement clazz) {
        List<Type> types = new ArrayList<>();
        for (ClassElement type : clazz.getInterfaces()) {
            types.add(ElementTypes.of(type));
        }
        return types;
    }

    @Override
    public @Nullable String containerOf(String annotation) {
        return ExtensionAnnotationTypes.declarationOf(annotation) instanceof AnnotationElement declaration
            ? declaration.getRepeatableContainer().orElse(null) : null;
    }

    @Override
    public boolean isTypeUse(String annotation) {
        return ExtensionAnnotationTypes.declarationOf(annotation) instanceof AnnotationElement declaration
            && declaration.getTargets().contains(ElementType.TYPE_USE);
    }
}
