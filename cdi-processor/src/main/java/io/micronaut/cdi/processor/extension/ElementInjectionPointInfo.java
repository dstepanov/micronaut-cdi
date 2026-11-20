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
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;

/**
 * An injection point of a bean, read off the field or parameter as it was compiled.
 *
 * @param type        The declared type
 * @param qualifiers  The qualifiers, {@code Default} where it declares none
 * @param declaration The field or parameter
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
record ElementInjectionPointInfo(Type type, Collection<AnnotationInfo> qualifiers, DeclarationInfo declaration)
    implements InjectionPointInfo {

    @Override
    public String toString() {
        return "InjectionPoint[" + type + " " + declaration + "]";
    }
}
