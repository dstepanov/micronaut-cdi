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
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

/**
 * A parameter of a method or a constructor, read from the Micronaut element that describes it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementParameterInfo extends ElementDeclarationInfo implements ParameterInfo {

    private final ParameterElement element;
    private final MethodInfo declaringMethod;

    ElementParameterInfo(ParameterElement element, MethodInfo declaringMethod) {
        super(element);
        this.element = element;
        this.declaringMethod = declaringMethod;
    }

    /**
     * The Micronaut parameter this describes.
     *
     * @return The parameter element
     */
    public ParameterElement parameterElement() {
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
    public MethodInfo declaringMethod() {
        return declaringMethod;
    }
}
