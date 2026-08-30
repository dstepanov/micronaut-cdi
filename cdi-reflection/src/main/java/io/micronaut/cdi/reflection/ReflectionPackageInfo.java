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
package io.micronaut.cdi.reflection;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedElement;

/**
 * A package, read back off the package itself.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionPackageInfo extends ReflectionAnnotations.Target implements PackageInfo {

    private final @Nullable Package declaringPackage;

    ReflectionPackageInfo(@Nullable Package declaringPackage) {
        this.declaringPackage = declaringPackage;
    }

    @Override
    AnnotatedElement annotated() {
        return declaringPackage == null ? Void.class : declaringPackage;
    }

    @Override
    public String name() {
        return declaringPackage == null ? "" : declaringPackage.getName();
    }

    @Override
    public String toString() {
        return name();
    }
}
