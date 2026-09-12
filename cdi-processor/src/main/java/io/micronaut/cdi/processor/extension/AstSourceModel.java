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
import java.util.ArrayList;
import java.util.List;

/**
 * The language model read from Micronaut's AST alone, in whichever language the compilation is in.
 *
 * <p>What it cannot answer, and answers as best the record allows: the annotations of a declaration are the
 * ones Micronaut recorded, so a repeatable annotation written once is reported inside its container and an
 * annotation Micronaut's own mappers added is reported beside the written ones; a primitive and each dimension
 * of an array carry no annotations; and the targets and the container of an annotation interface are not
 * known, so a constructor's return type carries nothing.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
class AstSourceModel implements SourceModel {

    static final AstSourceModel INSTANCE = new AstSourceModel();

    private static final String OBJECT = "java.lang.Object";

    AstSourceModel() {
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
        return found;
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
        return ElementTypes.of(field.getType());
    }

    @Override
    public Type typeOf(ParameterElement parameter) {
        return ElementTypes.of(parameter.getType());
    }

    @Override
    public Type returnTypeOf(MethodElement method) {
        return ElementTypes.of(method.getReturnType());
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
        return null;
    }

    @Override
    public boolean isTypeUse(String annotation) {
        return false;
    }
}
