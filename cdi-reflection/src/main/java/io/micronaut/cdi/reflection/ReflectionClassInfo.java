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
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A class, read back off the class itself.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectionClassInfo extends ReflectionAnnotations.Target implements ClassInfo {

    private final Class<?> type;

    ReflectionClassInfo(Class<?> type) {
        this.type = type;
    }

    /**
     * The class this describes.
     *
     * @return The class
     */
    public Class<?> type() {
        return type;
    }

    @Override
    AnnotatedElement annotated() {
        return type;
    }

    @Override
    public String name() {
        return type.getName();
    }

    @Override
    public String simpleName() {
        return type.getSimpleName();
    }

    @Override
    public PackageInfo packageInfo() {
        return new ReflectionPackageInfo(type.getPackage());
    }

    @Override
    public List<TypeVariable> typeParameters() {
        // a type variable of a declaration is not described here: what the model is read for is what the beans
        // of the container are, and a bean has a type rather than a variable
        return List.of();
    }

    @Override
    public @Nullable Type superClass() {
        return type.getGenericSuperclass() == null ? null : ReflectionTypes.of(type.getGenericSuperclass());
    }

    @Override
    public @Nullable ClassInfo superClassDeclaration() {
        return type.getSuperclass() == null ? null : new ReflectionClassInfo(type.getSuperclass());
    }

    @Override
    public List<Type> superInterfaces() {
        List<Type> interfaces = new ArrayList<>();
        for (java.lang.reflect.Type each : type.getGenericInterfaces()) {
            interfaces.add(ReflectionTypes.of(each));
        }
        return interfaces;
    }

    @Override
    public List<ClassInfo> superInterfacesDeclarations() {
        List<ClassInfo> interfaces = new ArrayList<>();
        for (Class<?> each : type.getInterfaces()) {
            interfaces.add(new ReflectionClassInfo(each));
        }
        return interfaces;
    }

    @Override
    public boolean isPlainClass() {
        return !isInterface() && !isEnum() && !isAnnotation() && !isRecord();
    }

    @Override
    public boolean isInterface() {
        return type.isInterface() && !type.isAnnotation();
    }

    @Override
    public boolean isEnum() {
        return type.isEnum();
    }

    @Override
    public boolean isAnnotation() {
        return type.isAnnotation();
    }

    @Override
    public boolean isRecord() {
        return type.isRecord();
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(type.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(type.getModifiers());
    }

    @Override
    public int modifiers() {
        return type.getModifiers();
    }

    @Override
    public Collection<MethodInfo> constructors() {
        List<MethodInfo> constructors = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            constructors.add(new ReflectionMethodInfo(constructor, this));
        }
        return constructors;
    }

    @Override
    public Collection<MethodInfo> methods() {
        List<MethodInfo> methods = new ArrayList<>();
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            for (Method method : each.getDeclaredMethods()) {
                if (!method.isSynthetic()) {
                    methods.add(new ReflectionMethodInfo(method, new ReflectionClassInfo(each)));
                }
            }
        }
        return methods;
    }

    @Override
    public Collection<FieldInfo> fields() {
        List<FieldInfo> fields = new ArrayList<>();
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            for (Field field : each.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    fields.add(new ReflectionFieldInfo(field, new ReflectionClassInfo(each)));
                }
            }
        }
        return fields;
    }

    @Override
    public Collection<RecordComponentInfo> recordComponents() {
        // a record component is described by the field and the accessor it stands for, both of which are
        // reported already
        return List.of();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReflectionClassInfo other && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return type.getName();
    }
}
