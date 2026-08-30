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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A class an extension is enhancing.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementClassConfig extends ElementDeclarationConfig<ClassConfig> implements ClassConfig {

    private final ClassElement element;
    private final ElementClassInfo info;

    ElementClassConfig(ClassElement element) {
        super(element);
        this.element = element;
        this.info = new ElementClassInfo(element);
    }

    @Override
    protected ClassConfig self() {
        return this;
    }

    @Override
    public ClassInfo info() {
        return info;
    }

    @Override
    public Collection<MethodConfig> constructors() {
        Collection<MethodConfig> constructors = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.CONSTRUCTORS)
            .forEach(constructor -> constructors.add(new ElementMethodConfig(constructor, info)));
        return constructors;
    }

    @Override
    public Collection<MethodConfig> methods() {
        Collection<MethodConfig> methods = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.ALL_METHODS)
            .forEach(method -> methods.add(new ElementMethodConfig(method, info)));
        return methods;
    }

    @Override
    public Collection<FieldConfig> fields() {
        Collection<FieldConfig> fields = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)
            .forEach(field -> fields.add(new ElementFieldConfig(field, info)));
        return fields;
    }
}
