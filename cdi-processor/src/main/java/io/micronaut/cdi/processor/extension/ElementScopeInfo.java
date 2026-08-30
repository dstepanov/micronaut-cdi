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
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

/**
 * The scope of a bean, named by the annotation it was written with.
 *
 * @param scope  The name of the scope annotation
 * @param normal Whether the scope is a normal one
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public record ElementScopeInfo(String scope, boolean normal) implements ScopeInfo {

    @Override
    public ClassInfo annotation() {
        throw new UnsupportedOperationException("The class that declares the scope " + scope + " is named rather "
            + "than read: a scope is recorded by name while the bean is compiled");
    }

    @Override
    public String name() {
        return scope;
    }

    @Override
    public boolean isNormal() {
        return normal;
    }

    @Override
    public String toString() {
        return "@" + scope;
    }
}
