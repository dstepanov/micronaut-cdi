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
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The language model of a Java compilation, which reads from the compiler's own elements what the AST does not
 * record and leaves the rest to the AST.
 *
 * <p>The compiler's annotation mirrors are the class file's view, where a single repetition stays itself, a
 * container stays separate and nothing Micronaut added appears, and the compiler fills a use out with the
 * defaults of the interface it names. Its types carry the annotation written on each dimension of an array and
 * on a primitive. Its annotation interfaces tell their targets and their container. Each of those is read here
 * for as long as the AST cannot answer it; see {@code MICRONAUT-CORE-FINDINGS.md}, findings 37 to 40.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class JavacSourceModel extends AstSourceModel {

    static final JavacSourceModel INSTANCE = new JavacSourceModel();

    private JavacSourceModel() {
    }

    /**
     * The annotations written on the declaration, read from the compiler's record, with Micronaut's record
     * consulted for what the compiler cannot know: the annotations an extension added during this compilation.
     */
    @Override
    public List<AnnotationInfo> annotationsOf(io.micronaut.inject.ast.Element element) {
        Element source = ExtensionSourceModel.sourceOf(element);
        if (source == null) {
            return super.annotationsOf(element);
        }
        List<AnnotationInfo> found = new ArrayList<>();
        // the names of Micronaut's record that the compiler's record already speaks for, a repeatable
        // annotation's container included: Micronaut names the container whether or not the source did
        Set<String> accounted = new LinkedHashSet<>();
        Elements utilities = ExtensionSourceModel.elementUtils();
        for (AnnotationMirror mirror : source.getAnnotationMirrors()) {
            String name = ExtensionSourceModel.nameOf(mirror);
            accounted.add(name);
            String container = ExtensionSourceModel.containerOf((TypeElement) mirror.getAnnotationType().asElement());
            if (container != null) {
                accounted.add(container);
            }
            if (ExtensionAnnotations.isReported(element, name)) {
                found.add(new MirrorAnnotationInfo(mirror, utilities));
            }
        }
        for (String name : element.getDeclaredAnnotationNames()) {
            if (accounted.contains(name) || !ExtensionAnnotations.isReported(element, name)) {
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
    public List<AnnotationInfo> repeatableOn(io.micronaut.inject.ast.Element element, String annotation) {
        if (ExtensionSourceModel.sourceOf(element) == null) {
            return super.repeatableOn(element, annotation);
        }
        return ExtensionAnnotations.repeatableIn(annotationsOf(element), annotation);
    }

    @Override
    public Type typeOf(FieldElement field) {
        return ExtensionSourceModel.sourceOf(field) instanceof VariableElement source
            ? MirrorTypes.ofDeclared(source.asType(), source) : super.typeOf(field);
    }

    @Override
    public Type typeOf(ParameterElement parameter) {
        return ExtensionSourceModel.sourceOf(parameter) instanceof VariableElement source
            ? MirrorTypes.ofDeclared(source.asType(), source) : super.typeOf(parameter);
    }

    @Override
    public Type returnTypeOf(MethodElement method) {
        return ExtensionSourceModel.sourceOf(method) instanceof ExecutableElement source
            ? MirrorTypes.ofDeclared(source.getReturnType(), source) : super.returnTypeOf(method);
    }

    @Override
    public ClassType constructorReturnTypeOf(MethodElement constructor) {
        Element source = ExtensionSourceModel.sourceOf(constructor);
        return source == null ? super.constructorReturnTypeOf(constructor)
            : MirrorTypes.ofConstructorReturn(constructor.getDeclaringType().getName(), source);
    }

    @Override
    public @Nullable Type receiverTypeOf(MethodElement method) {
        if (!(ExtensionSourceModel.sourceOf(method) instanceof ExecutableElement source)) {
            return super.receiverTypeOf(method);
        }
        // whether a method may declare a receiver, and what was written on the one it declares, is the
        // compiler's answer: the compiler answers the same way for a method that cannot declare a receiver and
        // for an instance method that may declare one but did not
        TypeMirror receiver = source.getReceiverType();
        if (receiver.getKind() != TypeKind.NONE) {
            return MirrorTypes.of(receiver);
        }
        ClassElement declaring = method.getDeclaringType();
        if (method.isStatic()
            || (method instanceof ConstructorElement && !(declaring.isInner() && !declaring.isStatic()))) {
            return null;
        }
        if (source.getEnclosingElement() instanceof TypeElement enclosing) {
            return MirrorTypes.of(enclosing.asType());
        }
        return ElementTypes.of(declaring);
    }

    @Override
    public List<Type> throwsTypesOf(MethodElement method) {
        if (!(ExtensionSourceModel.sourceOf(method) instanceof ExecutableElement source)) {
            return super.throwsTypesOf(method);
        }
        return source.getThrownTypes().stream().map(MirrorTypes::of).toList();
    }

    @Override
    public List<TypeVariable> typeParametersOf(ClassElement clazz) {
        if (!(ExtensionSourceModel.sourceOf(clazz) instanceof TypeElement source)) {
            return super.typeParametersOf(clazz);
        }
        return source.getTypeParameters().stream().map(MirrorTypes::ofParameter).toList();
    }

    @Override
    public List<TypeVariable> typeParametersOf(MethodElement method) {
        if (!(ExtensionSourceModel.sourceOf(method) instanceof ExecutableElement source)) {
            return super.typeParametersOf(method);
        }
        return source.getTypeParameters().stream().map(MirrorTypes::ofParameter).toList();
    }

    @Override
    public @Nullable Type superClassOf(ClassElement clazz) {
        if (!(ExtensionSourceModel.sourceOf(clazz) instanceof TypeElement source)) {
            return super.superClassOf(clazz);
        }
        // the compiler's own type, which carries the annotations and the arguments the extends clause wrote
        return MirrorTypes.ofPresent(source.getSuperclass());
    }

    @Override
    public List<Type> superInterfacesOf(ClassElement clazz) {
        if (!(ExtensionSourceModel.sourceOf(clazz) instanceof TypeElement source)) {
            return super.superInterfacesOf(clazz);
        }
        return source.getInterfaces().stream().map(MirrorTypes::of).toList();
    }

    @Override
    public @Nullable String containerOf(String annotation) {
        TypeElement declaration = annotationType(annotation);
        return declaration == null ? null : ExtensionSourceModel.containerOf(declaration);
    }

    @Override
    public boolean isTypeUse(String annotation) {
        TypeElement declaration = annotationType(annotation);
        if (declaration == null) {
            return false;
        }
        for (AnnotationMirror mirror : declaration.getAnnotationMirrors()) {
            if (!"java.lang.annotation.Target".equals(ExtensionSourceModel.nameOf(mirror))) {
                continue;
            }
            for (javax.lang.model.element.AnnotationValue value : mirror.getElementValues().values()) {
                if (!(value.getValue() instanceof List<?> targets)) {
                    continue;
                }
                for (Object target : targets) {
                    Object named = target instanceof javax.lang.model.element.AnnotationValue each
                        ? each.getValue() : target;
                    if (named instanceof VariableElement constant && "TYPE_USE".contentEquals(constant.getSimpleName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static @Nullable TypeElement annotationType(String annotation) {
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(annotation);
        if (declaration == null) {
            return null;
        }
        return ExtensionSourceModel.sourceOf(declaration) instanceof TypeElement type ? type : null;
    }
}
