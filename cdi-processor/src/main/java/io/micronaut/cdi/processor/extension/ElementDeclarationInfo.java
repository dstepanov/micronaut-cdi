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
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What every declaration of the language model has in common: the annotations written on it, read from the
 * Micronaut element it describes.
 *
 * <p>Only the annotations the element declares are reported. The annotation metadata Micronaut builds also
 * carries what a declaration inherits and what the annotations it carries are themselves annotated with, and an
 * extension asking what is written on a class is asking about what was written rather than about all of
 * that.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public abstract class ElementDeclarationInfo implements DeclarationInfo {

    private final Element element;

    protected ElementDeclarationInfo(Element element) {
        this.element = element;
    }

    /**
     * The Micronaut element this describes.
     *
     * @return The element
     */
    public final Element element() {
        return element;
    }

    @Override
    public final boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return element.hasDeclaredAnnotation(annotationType);
    }

    @Override
    public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public final <T extends Annotation> @Nullable AnnotationInfo annotation(Class<T> annotationType) {
        AnnotationValue<T> annotation = element.getDeclaredAnnotation(annotationType);
        return annotation == null ? null : new ElementAnnotationInfo(annotation);
    }

    @Override
    public final <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        List<AnnotationInfo> found = new ArrayList<>();
        for (AnnotationValue<T> annotation : element.getDeclaredAnnotationValuesByType(annotationType)) {
            found.add(new ElementAnnotationInfo(annotation));
        }
        return found;
    }

    @Override
    public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public final Collection<AnnotationInfo> annotations() {
        List<AnnotationInfo> found = new ArrayList<>();
        for (String name : element.getDeclaredAnnotationNames()) {
            AnnotationValue<Annotation> annotation = element.getDeclaredAnnotation(name);
            if (annotation != null) {
                found.add(new ElementAnnotationInfo(annotation));
            }
        }
        return found;
    }

    /**
     * The modifiers of an element, as the bits {@code java.lang.reflect.Modifier} describes them with, which is
     * what the language model reports.
     *
     * @param modifiers The Micronaut modifiers
     * @return The modifier bits
     */
    protected static int modifiersOf(Set<ElementModifier> modifiers) {
        int bits = 0;
        for (ElementModifier modifier : modifiers) {
            bits |= switch (modifier) {
                case PUBLIC -> Modifier.PUBLIC;
                case PROTECTED -> Modifier.PROTECTED;
                case PRIVATE -> Modifier.PRIVATE;
                case ABSTRACT -> Modifier.ABSTRACT;
                case STATIC -> Modifier.STATIC;
                case FINAL -> Modifier.FINAL;
                case TRANSIENT -> Modifier.TRANSIENT;
                case VOLATILE -> Modifier.VOLATILE;
                case SYNCHRONIZED -> Modifier.SYNCHRONIZED;
                case NATIVE -> Modifier.NATIVE;
                case STRICTFP -> Modifier.STRICT;
                default -> 0;
            };
        }
        return bits;
    }

    @Override
    public final boolean equals(Object o) {
        return o instanceof ElementDeclarationInfo other && element.equals(other.element);
    }

    @Override
    public final int hashCode() {
        return element.hashCode();
    }

    @Override
    public String toString() {
        return element.getName();
    }
}
