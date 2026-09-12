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
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.EnumElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private @Nullable ClassElement declaration;

    ElementClassInfo(ClassElement element) {
        super(element);
        this.element = element;
    }

    /**
     * The class a type names, as a declaration: a Micronaut element built for a use of a type answers for the use
     * (its arguments, the annotations written on it), and is resolved to the declaration of its name where the
     * compilation can see it.
     *
     * @param element The class, as used somewhere or as declared
     * @return The class
     */
    static ElementClassInfo declarationOf(ClassElement element) {
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(element.getName());
        return new ElementClassInfo(declaration != null ? declaration : element);
    }

    /**
     * The Micronaut class this describes.
     *
     * @return The class element
     */
    public ClassElement classElement() {
        return element;
    }

    /**
     * The element that answers what kind of class this is. Micronaut describes an enum and an annotation
     * interface by elements of their own, but only where it resolves the class by name; a use of the class is
     * a plain class element.
     */
    private ClassElement declaration() {
        if (declaration == null) {
            if (element instanceof AnnotationElement || element instanceof EnumElement) {
                declaration = element;
            } else {
                ClassElement resolved = ExtensionAnnotationTypes.declarationOf(element.getName());
                declaration = resolved != null ? resolved : element;
            }
        }
        return declaration;
    }

    /**
     * The annotations of the class, which are the ones it carries itself and the ones marked {@code Inherited}
     * that its superclasses carry. An annotation a subclass writes itself stands in for the one it would
     * otherwise inherit, and nothing is inherited from an interface.
     */
    @Override
    public Collection<AnnotationInfo> annotations() {
        List<AnnotationInfo> found = new ArrayList<>(super.annotations());
        Set<String> present = new HashSet<>();
        found.forEach(annotation -> present.add(annotation.name()));
        ClassElement superClass = element.getSuperType().orElse(null);
        while (superClass != null) {
            for (AnnotationInfo annotation : ExtensionAnnotations.declaredOn(superClass)) {
                if (ExtensionAnnotations.isInherited(annotation.name()) && present.add(annotation.name())) {
                    found.add(annotation);
                }
            }
            superClass = superClass.getSuperType().orElse(null);
        }
        return found;
    }

    /**
     * The repetitions of a repeatable annotation, which the nearest class of the hierarchy that carries any of
     * them answers for, the way reflection answers it: a subclass that repeats the annotation says nothing about
     * what its superclass repeated, so the two are never mixed.
     */
    @Override
    public <T extends java.lang.annotation.Annotation> Collection<AnnotationInfo> repeatableAnnotation(
        Class<T> annotationType) {
        ClassElement type = element;
        while (type != null) {
            List<AnnotationInfo> found = ExtensionAnnotations.repeatableOn(type, annotationType.getName());
            if (!found.isEmpty()) {
                return found;
            }
            if (!ExtensionAnnotations.isInherited(annotationType.getName())) {
                break;
            }
            type = type.getSuperType().orElse(null);
        }
        return List.of();
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
        return SourceModel.active().typeParametersOf(element);
    }

    @Override
    public @Nullable Type superClass() {
        return SourceModel.active().superClassOf(element);
    }

    @Override
    public @Nullable ClassInfo superClassDeclaration() {
        return element.getSuperType().map(ElementClassInfo::declarationOf).orElseGet(() -> {
            // a class whose superclass is Object still has one, though Micronaut's model leaves it implicit
            if (element.isInterface() || "java.lang.Object".equals(element.getName())) {
                return null;
            }
            ClassElement object = ExtensionAnnotationTypes.declarationOf("java.lang.Object");
            return object == null ? null : new ElementClassInfo(object);
        });
    }

    @Override
    public List<Type> superInterfaces() {
        return SourceModel.active().superInterfacesOf(element);
    }

    @Override
    public List<ClassInfo> superInterfacesDeclarations() {
        return element.getInterfaces().stream().map(i -> (ClassInfo) declarationOf(i)).toList();
    }

    @Override
    public boolean isPlainClass() {
        return !isInterface() && !isEnum() && !isAnnotation() && !isRecord();
    }

    @Override
    public boolean isInterface() {
        // Groovy describes an annotation interface as an interface too
        return element.isInterface() && !isAnnotation();
    }

    @Override
    public boolean isEnum() {
        return element.isEnum() || declaration() instanceof EnumElement;
    }

    @Override
    public boolean isAnnotation() {
        return declaration() instanceof AnnotationElement;
    }

    @Override
    public boolean isRecord() {
        return element.isRecord();
    }

    @Override
    public boolean isAbstract() {
        if (element.isAbstract()) {
            return true;
        }
        // an enum that declares an abstract method, for its constants to implement, is abstract in its class
        // file, which is what the model describes; no compiler says so of the enum itself, since the source may
        // not write the modifier on an enum
        return isEnum() && !element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared().onlyAbstract())
            .isEmpty();
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
        for (io.micronaut.inject.ast.MethodElement method : ElementMembers.methodsOf(element)) {
            // the class the method was declared by, which an inherited method's is not this one
            methods.add(new ElementMethodInfo(method, declarationOf(method.getDeclaringType())));
        }
        return methods;
    }

    @Override
    public Collection<FieldInfo> fields() {
        List<FieldInfo> fields = new ArrayList<>();
        for (io.micronaut.inject.ast.FieldElement field : ElementMembers.fieldsOf(element)) {
            fields.add(new ElementFieldInfo(field, declarationOf(field.getDeclaringType())));
        }
        return fields;
    }

    @Override
    public Collection<RecordComponentInfo> recordComponents() {
        // a record component is described by the field and the accessor it stands for, both of which are
        // reported already; it is not described again here
        return List.of();
    }
}
