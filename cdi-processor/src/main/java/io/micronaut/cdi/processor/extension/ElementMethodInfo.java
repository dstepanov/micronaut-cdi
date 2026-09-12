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

import javax.lang.model.element.ExecutableElement;

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
        // a constructor is named after the class it constructs, where Micronaut names it the way the class file
        // does
        return isConstructor() ? declaringClass.name() : element.getName();
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
        // a constructor returns the class it constructs, where both Micronaut and the compiler report the void a
        // class file declares; the annotations of the use are the ones written on the constructor
        if (isConstructor()) {
            javax.lang.model.element.Element source = source();
            if (source != null) {
                return MirrorTypes.ofConstructorReturn(element.getDeclaringType().getName(), source);
            }
            return ElementTypes.of(element.getDeclaringType());
        }
        if (source() instanceof ExecutableElement source) {
            return MirrorTypes.ofDeclared(source.getReturnType(), source);
        }
        return ElementTypes.of(element.getGenericReturnType());
    }

    @Override
    public @Nullable Type receiverType() {
        // whether a method may declare a receiver, and what was written on the one it declares, is the
        // compiler's answer: a static method has none, nor has the constructor of a class that is not inner
        if (source() instanceof ExecutableElement source) {
            javax.lang.model.type.TypeMirror receiver = source.getReceiverType();
            if (receiver.getKind() != javax.lang.model.type.TypeKind.NONE) {
                return MirrorTypes.of(receiver);
            }
            // the compiler answers the same way for a method that cannot declare a receiver and for an instance
            // method that may declare one but did not; the second still has the class that declares it as its
            // receiver, with nothing written on it
            if (element.isStatic() || isConstructor() && !element.getDeclaringType().isInner()) {
                return null;
            }
            if (source.getEnclosingElement() instanceof javax.lang.model.element.TypeElement declaring) {
                return MirrorTypes.of(declaring.asType());
            }
            return ElementTypes.of(element.getDeclaringType());
        }
        if (element.isStatic()) {
            return null;
        }
        return ElementTypes.of(element.getDeclaringType());
    }

    @Override
    public List<Type> throwsTypes() {
        if (source() instanceof ExecutableElement source) {
            return source.getThrownTypes().stream().map(MirrorTypes::of).toList();
        }
        return List.of(element.getThrownTypes()).stream().map(ElementTypes::of).toList();
    }

    @Override
    public List<TypeVariable> typeParameters() {
        if (source() instanceof ExecutableElement source) {
            return source.getTypeParameters().stream().map(MirrorTypes::ofParameter).toList();
        }
        // Micronaut records the arguments a method was called with rather than the variables it introduces
        return List.of();
    }

    private javax.lang.model.element.@Nullable Element source() {
        return ExtensionSourceModel.sourceOf(element);
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
