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
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * A method an extension is enhancing.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementMethodConfig extends ElementDeclarationConfig<MethodConfig> implements MethodConfig {

    private final MethodElement element;
    private final ElementMethodInfo info;

    ElementMethodConfig(MethodElement element, ClassInfo declaringClass) {
        super(element);
        this.element = element;
        this.info = new ElementMethodInfo(element, declaringClass);
    }

    @Override
    protected MethodConfig self() {
        return this;
    }

    @Override
    public MethodInfo info() {
        return info;
    }

    @Override
    public List<ParameterConfig> parameters() {
        List<ParameterConfig> parameters = new ArrayList<>();
        for (io.micronaut.inject.ast.ParameterElement parameter : element.getParameters()) {
            parameters.add(new ElementParameterConfig(parameter, info));
        }
        return parameters;
    }
}
