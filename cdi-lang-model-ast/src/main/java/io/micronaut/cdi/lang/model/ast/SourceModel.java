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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the language model reads of a declaration beyond what one Micronaut element answers on its own.
 *
 * <p>The AST answers nearly every question the language model of the specification asks, and
 * {@link AstSourceModel} answers them from it, in whichever language the compilation is in. One question it
 * cannot answer completely is the annotations of a declaration exactly as the source wrote them: a repeatable
 * annotation written beside an explicit container is folded into that container. This remaining limitation is
 * described under "Open points in Micronaut Core" in {@code CONFORMANCE.md}.
 * The annotation on a primitive type use, and the targets, the container and the retention
 * of an annotation interface, are answered by Micronaut Core 5.3.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
interface SourceModel {

    /**
     * The model for the compilation under way.
     *
     * @return The model
     */
    static SourceModel active() {
        return AstSourceModel.INSTANCE;
    }

    /**
     * The annotations written on a declaration, the ones an extension added included and the ones it removed
     * left out, retained until runtime only.
     *
     * @param element The declaration
     * @return The annotations
     */
    List<AnnotationInfo> annotationsOf(Element element);

    /**
     * The repetitions of a repeatable annotation on a declaration, written one by one or inside the container.
     *
     * @param element    The declaration
     * @param annotation The annotation interface's binary name
     * @return The repetitions, in the order written
     */
    List<AnnotationInfo> repeatableOn(Element element, String annotation);

    /**
     * The type of a field, as declared.
     *
     * @param field The field
     * @return The type
     */
    Type typeOf(FieldElement field);

    /**
     * The type of a parameter, as declared.
     *
     * @param parameter The parameter
     * @return The type
     */
    Type typeOf(ParameterElement parameter);

    /**
     * What a method returns, with the method's own type variables.
     *
     * @param method The method, not a constructor
     * @return The type
     */
    Type returnTypeOf(MethodElement method);

    /**
     * The class a constructor constructs, carrying the annotations written before the constructor's name that
     * may be written on a type use.
     *
     * @param constructor The constructor
     * @return The type
     */
    ClassType constructorReturnTypeOf(MethodElement constructor);

    /**
     * The receiver of a method: the declaring type for an instance method and for the constructor of an inner
     * class, carrying whatever was written on a receiver parameter; nothing for a static method or the
     * constructor of a class that is not inner.
     *
     * @param method The method
     * @return The receiver type, or {@code null}
     */
    @Nullable Type receiverTypeOf(MethodElement method);

    /**
     * The types a method declares it throws.
     *
     * @param method The method
     * @return The types
     */
    List<Type> throwsTypesOf(MethodElement method);

    /**
     * The type variables a class declares.
     *
     * @param clazz The class
     * @return The variables
     */
    List<TypeVariable> typeParametersOf(ClassElement clazz);

    /**
     * The type variables a method declares.
     *
     * @param method The method
     * @return The variables
     */
    List<TypeVariable> typeParametersOf(MethodElement method);

    /**
     * The superclass of a class as its {@code extends} clause wrote it; {@code java.lang.Object} when it wrote
     * none.
     *
     * @param clazz The class
     * @return The type, or {@code null} for an interface and for {@code java.lang.Object}
     */
    @Nullable Type superClassOf(ClassElement clazz);

    /**
     * The interfaces of a class as its {@code implements} clause wrote them.
     *
     * @param clazz The class
     * @return The types
     */
    List<Type> superInterfacesOf(ClassElement clazz);

    /**
     * The annotation interface holding the repetitions of a repeatable annotation.
     *
     * @param annotation The annotation interface's binary name
     * @return The container's binary name, or {@code null} when it is not repeatable or cannot be told
     */
    @Nullable String containerOf(String annotation);

    /**
     * Whether an annotation interface may be written on a use of a type.
     *
     * @param annotation The annotation interface's binary name
     * @return Whether {@code TYPE_USE} is among its targets; {@code false} when it cannot be told
     */
    boolean isTypeUse(String annotation);
}
