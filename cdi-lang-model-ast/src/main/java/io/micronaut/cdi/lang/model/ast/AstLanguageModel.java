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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

/**
 * The language model of CDI read from Micronaut's compile-time AST: the entry point of this module.
 *
 * <p>A class is handed in as the {@link ClassElement} a Micronaut visitor is given, and is answered as the
 * {@link ClassInfo} a build compatible extension reads; everything reached from it - members, annotations,
 * types - is read from the same AST, lazily. The model reports the annotations Micronaut retained until runtime,
 * narrowed by the {@link LanguageModelAnnotationFilter}s registered as services.</p>
 *
 * <p>It is the model read from the AST as a class compiles; a model read from loaded classes would be a sibling
 * of this one.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public final class AstLanguageModel {

    private static volatile @Nullable VisitorContext activeContext;

    private AstLanguageModel() {
    }

    /**
     * Gives the model the visitor context of the compilation under way: what it reads beyond the element at
     * hand - an annotation's retention, the defaults of its members, a class the element names - is asked of the
     * compiler through it.
     *
     * @param context The context, or {@code null} once the compilation is over
     */
    public static void useContext(@Nullable VisitorContext context) {
        activeContext = context;
    }

    /**
     * The visitor context of the compilation under way.
     *
     * @return The context, or {@code null} outside a compilation
     */
    static @Nullable VisitorContext activeContext() {
        return activeContext;
    }

    /**
     * The class, as the language model has it.
     *
     * @param element The class
     * @param context The visitor context the class was handed with, which the model reads the compilation through
     *                from here on (see {@link #useContext(VisitorContext)})
     * @return The class info
     */
    public static ClassInfo classInfo(ClassElement element, VisitorContext context) {
        useContext(context);
        return ElementClassInfo.declarationOf(element);
    }

    /**
     * A type, as the language model has it.
     *
     * @param type The type
     * @return The type
     */
    public static Type type(ClassElement type) {
        return ElementTypes.of(type);
    }
}
