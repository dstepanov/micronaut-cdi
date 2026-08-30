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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A class, read from the Micronaut element that describes it.
 *
 * <p>The members it reports are the ones the class and its superclasses declare, which is what an extension
 * enhancing a class expects to be handed: an annotation put on a method of a superclass applies to the subclass
 * that inherits it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementClassInfo extends ElementDeclarationInfo implements ClassInfo {

    private final ClassElement element;

    ElementClassInfo(ClassElement element) {
        super(element);
        this.element = element;
    }

    /**
     * The Micronaut class this describes.
     *
     * @return The class element
     */
    public ClassElement classElement() {
        return element;
    }

    @Override
    public String name() {
        return element.getName();
    }

    @Override
    public String simpleName() {
        // the simple name of a nested class is its own, not outer-dollar-inner, which is how the language
        // model of the specification reads (and how java.lang.Class#getSimpleName answers)
        String simple = element.getSimpleName();
        int nested = simple.lastIndexOf('$');
        return nested < 0 ? simple : simple.substring(nested + 1);
    }

    @Override
    public PackageInfo packageInfo() {
        return new ElementPackageInfo(element.getPackage());
    }

    @Override
    public List<TypeVariable> typeParameters() {
        // Micronaut records the arguments a type was used with rather than the variables its declaration
        // introduces, and an extension that reads them would be reading something else
        return List.of();
    }

    @Override
    public @Nullable Type superClass() {
        return element.getSuperType().map(ElementTypes::of).orElse(null);
    }

    @Override
    public @Nullable ClassInfo superClassDeclaration() {
        return element.getSuperType().map(ElementClassInfo::new).orElseGet(() -> {
            // a class whose superclass is Object still has one, though Micronaut's model leaves it implicit
            if (element.isInterface() || "java.lang.Object".equals(element.getName())) {
                return null;
            }
            io.micronaut.inject.visitor.VisitorContext context =
                BuildCompatibleExtensionVisitor.activeVisitorContext();
            return context == null ? null
                : context.getClassElement("java.lang.Object").map(ElementClassInfo::new).orElse(null);
        });
    }

    @Override
    public List<Type> superInterfaces() {
        return element.getInterfaces().stream().map(ElementTypes::of).toList();
    }

    @Override
    public List<ClassInfo> superInterfacesDeclarations() {
        return element.getInterfaces().stream().map(i -> (ClassInfo) new ElementClassInfo(i)).toList();
    }

    @Override
    public boolean isPlainClass() {
        return !isInterface() && !isEnum() && !isAnnotation() && !isRecord();
    }

    @Override
    public boolean isInterface() {
        return element.isInterface() && !isAnnotation();
    }

    @Override
    public boolean isEnum() {
        return element.isEnum();
    }

    @Override
    public boolean isAnnotation() {
        return element.isAssignable(java.lang.annotation.Annotation.class) && element.isInterface();
    }

    @Override
    public boolean isRecord() {
        return element.isRecord();
    }

    @Override
    public boolean isAbstract() {
        return element.isAbstract();
    }

    @Override
    public boolean isFinal() {
        return element.isFinal();
    }

    @Override
    public int modifiers() {
        return modifiersOf(element.getModifiers());
    }

    @Override
    public Collection<MethodInfo> constructors() {
        List<MethodInfo> constructors = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.CONSTRUCTORS)
            .forEach(constructor -> constructors.add(new ElementMethodInfo(constructor, this)));
        return constructors;
    }

    @Override
    public Collection<MethodInfo> methods() {
        List<MethodInfo> methods = new ArrayList<>();
        // overridden declarations included: the model describes every method the class has, and an extension
        // filtering by the declaring type expects to find an interface's own declaration under the interface
        element.getEnclosedElements(ElementQuery.ALL_METHODS.includeOverriddenMethods())
            .forEach(method -> methods.add(new ElementMethodInfo(method,
                // the class the method was declared by, which an inherited method's is not this one
                new ElementClassInfo(method.getDeclaringType()))));
        return methods;
    }

    @Override
    public Collection<FieldInfo> fields() {
        List<FieldInfo> fields = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)
            .forEach(field -> fields.add(new ElementFieldInfo(field, this)));
        return fields;
    }

    @Override
    public Collection<RecordComponentInfo> recordComponents() {
        // a record component is described by the field and the accessor it stands for, both of which are
        // reported already; it is not described again here
        return List.of();
    }
}
