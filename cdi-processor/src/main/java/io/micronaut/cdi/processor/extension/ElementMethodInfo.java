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
import io.micronaut.inject.ast.MethodElement;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A method or a constructor, read from the Micronaut element that describes it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementMethodInfo extends ElementDeclarationInfo implements MethodInfo {

    private final MethodElement element;
    private final ClassInfo declaringClass;

    ElementMethodInfo(MethodElement element, ClassInfo declaringClass) {
        super(element);
        this.element = element;
        this.declaringClass = declaringClass;
    }

    /**
     * The Micronaut method this describes.
     *
     * @return The method element
     */
    public MethodElement methodElement() {
        return element;
    }

    @Override
    public String name() {
        return element.getName();
    }

    @Override
    public List<ParameterInfo> parameters() {
        List<ParameterInfo> parameters = new ArrayList<>();
        for (io.micronaut.inject.ast.ParameterElement parameter : element.getParameters()) {
            parameters.add(new ElementParameterInfo(parameter, this));
        }
        return parameters;
    }

    @Override
    public Type returnType() {
        return ElementTypes.of(element.getGenericReturnType());
    }

    @Override
    public @Nullable Type receiverType() {
        // the receiver of an instance method is the class that declares it, and a static method has none
        if (element.isStatic()) {
            return null;
        }
        return ElementTypes.of(element.getDeclaringType());
    }

    @Override
    public List<Type> throwsTypes() {
        return List.of(element.getThrownTypes()).stream().map(ElementTypes::of).toList();
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public boolean isConstructor() {
        return element instanceof io.micronaut.inject.ast.ConstructorElement;
    }

    @Override
    public boolean isStatic() {
        return element.isStatic();
    }

    @Override
    public boolean isAbstract() {
        return element.isAbstract();
    }

    @Override
    public boolean isFinal() {
        return element.isFinal();
    }

    @Override
    public int modifiers() {
        return modifiersOf(element.getModifiers());
    }

    @Override
    public ClassInfo declaringClass() {
        return declaringClass;
    }
}
