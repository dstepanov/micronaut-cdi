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
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Parameter;

/**
 * A parameter, read back off the method that declares it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionParameterInfo extends ReflectionAnnotations.Target implements ParameterInfo {

    private final Parameter parameter;
    private final MethodInfo declaringMethod;

    ReflectionParameterInfo(Parameter parameter, MethodInfo declaringMethod) {
        this.parameter = parameter;
        this.declaringMethod = declaringMethod;
    }

    @Override
    AnnotatedElement annotated() {
        return parameter;
    }

    @Override
    public String name() {
        return parameter.getName();
    }

    @Override
    public Type type() {
        return ReflectionTypes.of(parameter.getParameterizedType());
    }

    @Override
    public MethodInfo declaringMethod() {
        return declaringMethod;
    }

    @Override
    public String toString() {
        return parameter.toString();
    }
}
