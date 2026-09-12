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
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What every declaration of the language model has in common: the annotations written on it.
 *
 * <p>Only the annotations the declaration itself carries are reported, and of those only the ones retained until
 * runtime. The annotation metadata Micronaut builds also carries what a declaration inherits and what the
 * annotations it carries are themselves annotated with, and an extension asking what is written on a class is
 * asking about what was written rather than about all of that; the compiler sees the annotations retained in the
 * source and in the class file too, and the language model is the model a runtime extension would read. Where the
 * annotations come from, and what is left out of them, is {@link ExtensionAnnotations}.</p>
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
        return annotation(annotationType) != null;
    }

    @Override
    public final boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public final <T extends Annotation> @Nullable AnnotationInfo annotation(Class<T> annotationType) {
        for (AnnotationInfo annotation : annotations()) {
            if (annotation.name().equals(annotationType.getName())) {
                return annotation;
            }
        }
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return ExtensionAnnotations.repeatableOn(element, annotationType.getName());
    }

    @Override
    public final Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return ExtensionAnnotations.declaredOn(element);
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
