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
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * The compiler's own view of a declaration, reached through the native element Micronaut wraps.
 *
 * <p>The language model of the specification describes a declaration the way its source reads, and Micronaut's
 * metadata is a record made for a different purpose: it folds a repeatable annotation into its container whether
 * or not the source wrote the container, leaves the defaults of an annotation's members out, and keeps a class
 * named by an annotation member as a name rather than as a type. None of that is a shortcoming of the record —
 * it is what a bean definition needs — but it is not what an extension reading the language model is promised.
 * Where the compiler's element is reachable, it is the more faithful source and is read instead.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionSourceModel {

    private ExtensionSourceModel() {
    }

    /**
     * The compiler's element behind a Micronaut element.
     *
     * @param element The Micronaut element
     * @return The compiler's element, or {@code null} when the compilation is not one this can reach into
     */
    static @Nullable Element sourceOf(io.micronaut.inject.ast.Element element) {
        return unwrap(element.getNativeType());
    }

    /**
     * The compiler's element utilities, which answer what the defaults of an annotation's members are.
     *
     * @return The utilities, or {@code null} when the compilation is not one this can reach into
     */
    static @Nullable Elements elementUtils() {
        VisitorContext context = BuildCompatibleExtensionVisitor.activeVisitorContext();
        if (context == null) {
            return null;
        }
        try {
            // the Java implementation of the visitor context holds them; this module compiles against the
            // language-neutral interface, so they are asked for by name
            Object utilities = context.getClass().getMethod("getElements").invoke(context);
            return utilities instanceof Elements elements ? elements : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The name of the annotation interface a mirror is a use of.
     *
     * @param mirror The mirror
     * @return The binary name
     */
    static String nameOf(AnnotationMirror mirror) {
        return nameOf((TypeElement) mirror.getAnnotationType().asElement());
    }

    /**
     * The binary name of a type the compiler describes.
     *
     * @param type The type
     * @return The binary name
     */
    static String nameOf(TypeElement type) {
        // the binary name of a nested class is outer-dollar-inner, which the qualified name is not
        Element enclosing = type.getEnclosingElement();
        if (enclosing instanceof TypeElement outer) {
            return nameOf(outer) + "$" + type.getSimpleName();
        }
        return type.getQualifiedName().toString();
    }

    /**
     * The annotation interface that holds the repetitions of a repeatable annotation.
     *
     * @param annotation The annotation interface
     * @return The container's binary name, or {@code null} when the annotation is not repeatable
     */
    static @Nullable String containerOf(TypeElement annotation) {
        for (AnnotationMirror mirror : annotation.getAnnotationMirrors()) {
            if (!"java.lang.annotation.Repeatable".equals(nameOf(mirror))) {
                continue;
            }
            for (AnnotationValue value : mirror.getElementValues().values()) {
                if (value.getValue() instanceof DeclaredType container) {
                    return nameOf((TypeElement) container.asElement());
                }
            }
        }
        return null;
    }

    /**
     * The language model's type for a type the compiler describes, which is what an annotation member naming a
     * class is read as.
     *
     * @param type The compiler's type
     * @return The type
     */
    static Type typeOf(TypeMirror type) {
        return switch (type.getKind()) {
            case VOID -> ElementTypes.of(io.micronaut.inject.ast.PrimitiveElement.VOID);
            case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR ->
                ElementTypes.of(io.micronaut.inject.ast.PrimitiveElement
                    .valueOf(type.getKind().name().toLowerCase(java.util.Locale.ROOT)));
            case ARRAY -> ElementTypes.of(ElementTypes
                .elementOf(typeOf(((ArrayType) type).getComponentType())).toArray());
            case DECLARED -> classOf(nameOf((TypeElement) ((DeclaredType) type).asElement()));
            default -> throw new IllegalStateException("The type " + type + " is not one an annotation member names");
        };
    }

    /**
     * The language model's type for a class of the given binary name.
     *
     * @param name The binary name
     * @return The type
     */
    static Type classOf(String name) {
        io.micronaut.inject.ast.ClassElement element = ExtensionAnnotationTypes.declarationOf(name);
        if (element == null) {
            throw new IllegalStateException("The type " + name + " is not on the compilation's classpath");
        }
        return ElementTypes.of(element);
    }

    /**
     * The compiler's element inside whatever Micronaut wrapped it in, which differs between Micronaut
     * versions: the element itself, or a holder with an {@code element()} accessor.
     */
    private static @Nullable Element unwrap(@Nullable Object nativeType) {
        if (nativeType instanceof Element element) {
            return element;
        }
        if (nativeType == null) {
            return null;
        }
        try {
            Object unwrapped = nativeType.getClass().getMethod("element").invoke(nativeType);
            if (unwrapped instanceof Element element) {
                return element;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a holder this build knows
        }
        return null;
    }
}
