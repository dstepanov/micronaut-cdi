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
import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;

/**
 * A parameter an extension is enhancing.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementParameterConfig extends ElementDeclarationConfig<ParameterConfig>
    implements ParameterConfig {

    private final ElementParameterInfo info;

    ElementParameterConfig(ParameterElement element, MethodInfo declaringMethod) {
        super(element);
        this.info = new ElementParameterInfo(element, declaringMethod);
    }

    @Override
    protected ParameterConfig self() {
        return this;
    }

    @Override
    public ParameterInfo info() {
        return info;
    }
}
