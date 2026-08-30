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
import io.micronaut.inject.ast.PackageElement;
import jakarta.enterprise.lang.model.declarations.PackageInfo;

/**
 * A package, read from the Micronaut element that describes it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementPackageInfo extends ElementDeclarationInfo implements PackageInfo {

    private final PackageElement element;

    ElementPackageInfo(PackageElement element) {
        super(element);
        this.element = element;
    }

    @Override
    public String name() {
        return element.getName();
    }
}
