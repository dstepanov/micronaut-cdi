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
import io.micronaut.inject.ast.Element;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The annotations the language model reports for one declaration.
 *
 * <p>Two sources are combined. The compiler's own record of what the source wrote is the first and the better
 * one: it keeps a repeatable annotation written once apart from one written twice and folded into its container,
 * which Micronaut's metadata does not, and it carries the types and the member defaults the model asks about.
 * Micronaut's metadata is then read for the annotations the source did not write — the ones an extension added
 * during this compilation, and the ones Micronaut's own mappers put there — since those exist nowhere else.</p>
 *
 * <p>Annotations not retained until runtime are left out of both, and so is anything an extension took off the
 * declaration: a removal is recorded rather than applied to the source the compiler already read.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionAnnotations {

    private ExtensionAnnotations() {
    }

    /**
     * The annotations written on a declaration, the ones an extension added included.
     *
     * @param element The declaration
     * @return The annotations
     */
    static List<AnnotationInfo> declaredOn(Element element) {
        List<AnnotationInfo> found = new ArrayList<>();
        // the names of Micronaut's record that the compiler's record already speaks for, a repeatable
        // annotation's container included: Micronaut names the container whether or not the source did
        Set<String> accounted = new LinkedHashSet<>();
        javax.lang.model.element.Element source = ExtensionSourceModel.sourceOf(element);
        if (source != null) {
            Elements utilities = ExtensionSourceModel.elementUtils();
            for (AnnotationMirror mirror : source.getAnnotationMirrors()) {
                String name = ExtensionSourceModel.nameOf(mirror);
                accounted.add(name);
                String container =
                    ExtensionSourceModel.containerOf((TypeElement) mirror.getAnnotationType().asElement());
                if (container != null) {
                    accounted.add(container);
                }
                if (isReported(element, name)) {
                    found.add(new MirrorAnnotationInfo(mirror, utilities));
                }
            }
        }
        for (String name : element.getDeclaredAnnotationNames()) {
            if (accounted.contains(name) || !isReported(element, name)) {
                continue;
            }
            AnnotationValue<Annotation> annotation = element.getDeclaredAnnotation(name);
            if (annotation != null) {
                found.add(new ElementAnnotationInfo(annotation));
            }
        }
        return found;
    }

    /**
     * The annotations of one interface that a declaration carries, whether written one at a time or written
     * inside the container of a repeatable annotation. This is what the model answers for a repeatable
     * annotation, and it is what reflection answers too.
     *
     * @param element    The declaration
     * @param annotation The annotation interface's binary name
     * @return The annotations, in the order they were written
     */
    static List<AnnotationInfo> repeatableOn(Element element, String annotation) {
        return repeatableIn(declaredOn(element), annotation);
    }

    /**
     * The annotations of one interface among the given ones, whether written one at a time or written inside the
     * container of a repeatable annotation.
     *
     * @param annotations The annotations to look through
     * @param annotation  The annotation interface's binary name
     * @return The annotations, in the order they were written
     */
    static List<AnnotationInfo> repeatableIn(Collection<AnnotationInfo> annotations, String annotation) {
        String container = containerOf(annotation);
        List<AnnotationInfo> found = new ArrayList<>();
        for (AnnotationInfo written : annotations) {
            if (written.name().equals(annotation)) {
                found.add(written);
            } else if (written.name().equals(container) && written.hasValue()) {
                for (AnnotationMember repetition : written.value().asArray()) {
                    found.add(repetition.asNestedAnnotation());
                }
            }
        }
        return found;
    }

    /**
     * Whether an annotation of the given name is passed on to a class from its superclass.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether it is marked {@code Inherited}
     */
    static boolean isInherited(String annotation) {
        TypeElement declaration = annotationType(annotation);
        if (declaration == null) {
            return false;
        }
        for (AnnotationMirror mirror : declaration.getAnnotationMirrors()) {
            if ("java.lang.annotation.Inherited".equals(ExtensionSourceModel.nameOf(mirror))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The interface that holds the repetitions of a repeatable annotation.
     *
     * @param annotation The annotation interface's binary name
     * @return The container's binary name, or {@code null} when the annotation is not repeatable
     */
    static @Nullable String containerOf(String annotation) {
        TypeElement declaration = annotationType(annotation);
        return declaration == null ? null : ExtensionSourceModel.containerOf(declaration);
    }

    /**
     * Whether an annotation of the given name may be written on a use of a type, which is what distinguishes the
     * annotations of a constructor that belong to the class it constructs from the ones that belong to the
     * constructor alone.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether {@code TYPE_USE} is among its targets
     */
    static boolean isTypeUse(String annotation) {
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
                    if (named instanceof javax.lang.model.element.VariableElement constant
                        && "TYPE_USE".contentEquals(constant.getSimpleName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isReported(Element element, String annotation) {
        return ExtensionAnnotationTypes.isRuntimeRetained(annotation)
            && !RemovedAnnotations.isRemoved(element, annotation);
    }

    private static @Nullable TypeElement annotationType(String annotation) {
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(annotation);
        if (declaration == null) {
            return null;
        }
        return ExtensionSourceModel.sourceOf(declaration) instanceof TypeElement type ? type : null;
    }
}
