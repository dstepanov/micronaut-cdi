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
import io.micronaut.inject.ast.FieldElement;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.types.Type;

/**
 * A field, read from the Micronaut element that describes it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementFieldInfo extends ElementDeclarationInfo implements FieldInfo {

    private final FieldElement element;
    private final ClassInfo declaringClass;

    ElementFieldInfo(FieldElement element, ClassInfo declaringClass) {
        super(element);
        this.element = element;
        this.declaringClass = declaringClass;
    }

    /**
     * The Micronaut field this describes.
     *
     * @return The field element
     */
    public FieldElement fieldElement() {
        return element;
    }

    @Override
    public String name() {
        return element.getName();
    }

    @Override
    public Type type() {
        return ElementTypes.of(element.getGenericType());
    }

    @Override
    public boolean isStatic() {
        return element.isStatic();
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
