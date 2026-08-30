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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * A method or a constructor, read back off the class.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionMethodInfo extends ReflectionAnnotations.Target implements MethodInfo {

    private final Executable executable;
    private final ClassInfo declaringClass;

    ReflectionMethodInfo(Executable executable, ClassInfo declaringClass) {
        this.executable = executable;
        this.declaringClass = declaringClass;
    }

    /**
     * The method or constructor this describes.
     *
     * @return The executable
     */
    public Executable executable() {
        return executable;
    }

    @Override
    AnnotatedElement annotated() {
        return executable;
    }

    @Override
    public String name() {
        return executable.getName();
    }

    @Override
    public List<ParameterInfo> parameters() {
        List<ParameterInfo> parameters = new ArrayList<>();
        for (Parameter parameter : executable.getParameters()) {
            parameters.add(new ReflectionParameterInfo(parameter, this));
        }
        return parameters;
    }

    @Override
    public Type returnType() {
        return executable instanceof Method method
            ? ReflectionTypes.of(method.getGenericReturnType())
            : ReflectionTypes.of(executable.getDeclaringClass());
    }

    @Override
    public @Nullable Type receiverType() {
        return isStatic() ? null : ReflectionTypes.of(executable.getDeclaringClass());
    }

    @Override
    public List<Type> throwsTypes() {
        List<Type> thrown = new ArrayList<>();
        for (java.lang.reflect.Type each : executable.getGenericExceptionTypes()) {
            thrown.add(ReflectionTypes.of(each));
        }
        return thrown;
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public boolean isConstructor() {
        return executable instanceof Constructor<?>;
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(executable.getModifiers());
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(executable.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(executable.getModifiers());
    }

    @Override
    public int modifiers() {
        return executable.getModifiers();
    }

    @Override
    public ClassInfo declaringClass() {
        return declaringClass;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReflectionMethodInfo other && executable.equals(other.executable);
    }

    @Override
    public int hashCode() {
        return executable.hashCode();
    }

    @Override
    public String toString() {
        return executable.toString();
    }
}
