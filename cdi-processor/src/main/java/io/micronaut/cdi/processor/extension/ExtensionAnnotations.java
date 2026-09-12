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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.Element;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The annotations of the language model: what a declaration carries, and what is known of an annotation
 * interface.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionAnnotations {

    /**
     * The packages of the annotations Micronaut's mappers and remappers write into its record, this project's
     * own included.
     */
    private static final String[] MICRONAUT_PACKAGES = {
        "io.micronaut.core.annotation.", "io.micronaut.context.annotation.", "io.micronaut.inject.annotation.",
        "io.micronaut.aop.", "io.micronaut.runtime.", "io.micronaut.cdi.annotation.", "io.micronaut.cdi.processor.",
    };

    private ExtensionAnnotations() {
    }

    /**
     * The annotations written on a declaration, the ones an extension added included.
     *
     * @param element The declaration
     * @return The annotations
     */
    static List<AnnotationInfo> declaredOn(Element element) {
        return SourceModel.active().annotationsOf(element);
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
        return SourceModel.active().repeatableOn(element, annotation);
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
        String container = SourceModel.active().containerOf(annotation);
        List<AnnotationInfo> found = new ArrayList<>();
        for (AnnotationInfo written : annotations) {
            if (written.name().equals(annotation)) {
                found.add(written);
            } else if (container != null ? written.name().equals(container) : holdsRepetitionsOf(written, annotation)) {
                for (AnnotationMember repetition : written.value().asArray()) {
                    found.add(repetition.asNestedAnnotation());
                }
            }
        }
        return found;
    }

    /**
     * Whether an annotation is, by its shape, the container of repetitions of the given annotation: its value
     * is an array of nested annotations, every one of the given interface. Asked when the container cannot be
     * named, which is the shape Micronaut itself goes by.
     */
    private static boolean holdsRepetitionsOf(AnnotationInfo candidate, String annotation) {
        if (!candidate.hasValue() || !candidate.value().isArray()) {
            return false;
        }
        List<AnnotationMember> members = candidate.value().asArray();
        if (members.isEmpty()) {
            return false;
        }
        for (AnnotationMember member : members) {
            if (!member.isNestedAnnotation() || !member.asNestedAnnotation().name().equals(annotation)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an annotation of the given name is passed on to a class from its superclass.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether it is marked {@code Inherited}
     */
    static boolean isInherited(String annotation) {
        return ExtensionAnnotationTypes.declarationOf(annotation) instanceof AnnotationElement declaration
            && declaration.isInherited();
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
        return SourceModel.active().isTypeUse(annotation);
    }

    /**
     * Whether an annotation of the given name is reported on a declaration: retained until runtime, and not
     * taken off the declaration by an extension.
     *
     * @param element    The declaration
     * @param annotation The annotation interface's binary name
     * @return Whether it is reported
     */
    static boolean isReported(Element element, String annotation) {
        return ExtensionAnnotationTypes.isRuntimeRetained(annotation)
            && !isSynthesised(annotation)
            && !RemovedAnnotations.isRemoved(element, annotation);
    }

    /**
     * Whether an annotation of the given name is one Micronaut writes into its record rather than one the
     * source wrote: what its mappers and remappers add lives in Micronaut's own packages, and the source of a
     * class read by a build compatible extension does not. Until Micronaut records the annotations as written
     * ({@code MICRONAUT-CORE-FINDINGS.md}, finding 39), these are left out.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether it is Micronaut's own
     */
    static boolean isSynthesised(String annotation) {
        for (String prefix : MICRONAUT_PACKAGES) {
            if (annotation.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
