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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import org.jspecify.annotations.Nullable;

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
     * Whether an annotation of the given name is reported on a declaration: retained until runtime, allowed by
     * every registered {@link LanguageModelAnnotationFilter}, and not taken off the declaration by an extension.
     *
     * @param element    The declaration
     * @param annotation The annotation interface's binary name
     * @return Whether it is reported
     */
    static boolean isReported(Element element, String annotation) {
        return ExtensionAnnotationTypes.isRuntimeRetained(annotation)
            && isAllowed(annotation, new LanguageModelAnnotationFilter.Place(element, declaringTypeOf(element), false))
            && !RemovedAnnotations.isRemoved(element, annotation);
    }

    /**
     * Whether an annotation of the given name, recorded on a use of a type, is reported: retained until runtime
     * and allowed by every registered {@link LanguageModelAnnotationFilter}.
     *
     * @param annotation    The annotation interface's binary name
     * @param declaringType The class the use belongs to, or {@code null} where unknown
     * @return Whether it is reported
     */
    static boolean isReportedOnType(String annotation, @Nullable ClassElement declaringType) {
        return ExtensionAnnotationTypes.isRuntimeRetained(annotation)
            && isAllowed(annotation, new LanguageModelAnnotationFilter.Place(null, declaringType, true));
    }

    /**
     * The annotations as the language model reports them, from the ones Micronaut recorded: a repeatable
     * annotation that Micronaut folded into its container although it was written once is reported as itself,
     * which is what reflection reports of a single repetition too. A container the source wrote around one
     * repetition reads the same way, which is the one shape this cannot tell apart.
     *
     * @param found    The annotations Micronaut recorded, already filtered
     * @param metadata The record they came from, which knows the container of each repeatable annotation
     * @return The annotations to report
     */
    static List<AnnotationInfo> unfoldSingleRepetitions(List<AnnotationInfo> found, AnnotationMetadata metadata) {
        List<AnnotationInfo> reported = new ArrayList<>(found.size());
        for (AnnotationInfo annotation : found) {
            AnnotationInfo single = singleRepetitionIn(annotation, metadata);
            reported.add(single != null ? single : annotation);
        }
        return reported;
    }

    private static @Nullable AnnotationInfo singleRepetitionIn(AnnotationInfo candidate, AnnotationMetadata metadata) {
        if (!(candidate instanceof ElementAnnotationInfo info) || !candidate.hasValue()
            || !candidate.value().isArray()) {
            return null;
        }
        List<AnnotationMember> members = candidate.value().asArray();
        if (members.size() != 1 || !members.get(0).isNestedAnnotation()) {
            return null;
        }
        AnnotationInfo repetition = members.get(0).asNestedAnnotation();
        // Micronaut answers for the repetitions of a repeatable annotation by the annotation's own name, through
        // the container it folded them into: one answer under that name means one repetition inside this container
        if (!metadata.getDeclaredAnnotationNames().contains(info.name())
            || metadata.getDeclaredAnnotationValuesByName(repetition.name()).size() != 1) {
            return null;
        }
        return repetition;
    }

    private static boolean isAllowed(String annotation, LanguageModelAnnotationFilter.Place place) {
        for (LanguageModelAnnotationFilter filter : LanguageModelAnnotationFilter.registered()) {
            if (!filter.isReported(annotation, place)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable ClassElement declaringTypeOf(Element element) {
        if (element instanceof ClassElement clazz) {
            return clazz;
        }
        if (element instanceof MemberElement member) {
            return member.getDeclaringType();
        }
        if (element instanceof ParameterElement parameter) {
            return parameter.getMethodElement().getDeclaringType();
        }
        return null;
    }
}
