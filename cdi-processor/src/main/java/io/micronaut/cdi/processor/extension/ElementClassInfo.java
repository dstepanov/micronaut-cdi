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
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.TypeElement;

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
        // Micronaut records the arguments a type was used with rather than the variables its declaration
        // introduces, and an extension that reads them would be reading something else
        if (source() instanceof TypeElement source) {
            return source.getTypeParameters().stream().map(MirrorTypes::ofParameter).toList();
        }
        return List.of();
    }

    @Override
    public @Nullable Type superClass() {
        // the compiler's own type, which carries the annotations and the arguments the extends clause wrote
        if (source() instanceof TypeElement source) {
            return MirrorTypes.ofPresent(source.getSuperclass());
        }
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
        if (source() instanceof TypeElement source) {
            return source.getInterfaces().stream().map(MirrorTypes::of).toList();
        }
        return element.getInterfaces().stream().map(ElementTypes::of).toList();
    }

    private javax.lang.model.element.@Nullable Element source() {
        return ExtensionSourceModel.sourceOf(element);
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
        return isKind(javax.lang.model.element.ElementKind.INTERFACE, element.isInterface() && !isAnnotation());
    }

    @Override
    public boolean isEnum() {
        return isKind(javax.lang.model.element.ElementKind.ENUM, element.isEnum());
    }

    @Override
    public boolean isAnnotation() {
        // Micronaut has no kind of its own for an annotation interface, and neither being assignable to
        // Annotation nor being an interface is what makes a class one
        return isKind(javax.lang.model.element.ElementKind.ANNOTATION_TYPE,
            element.isAssignable(java.lang.annotation.Annotation.class) && element.isInterface());
    }

    @Override
    public boolean isRecord() {
        return isKind(javax.lang.model.element.ElementKind.RECORD, element.isRecord());
    }

    @Override
    public boolean isAbstract() {
        if (source() instanceof TypeElement source) {
            if (source.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
                return true;
            }
            // an enum that declares an abstract method, for its constants to implement, is abstract in its class
            // file, which is what the model describes; the compiler's element does not say so while the class is
            // compiled, since the source may not write the modifier on an enum
            return source.getKind() == javax.lang.model.element.ElementKind.ENUM
                && source.getEnclosedElements().stream().anyMatch(member ->
                    member.getKind() == javax.lang.model.element.ElementKind.METHOD
                        && member.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT));
        }
        return element.isAbstract();
    }

    private boolean isKind(javax.lang.model.element.ElementKind kind, boolean otherwise) {
        return source() instanceof TypeElement source ? source.getKind() == kind : otherwise;
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
            methods.add(new ElementMethodInfo(method, new ElementClassInfo(method.getDeclaringType())));
        }
        return methods;
    }

    @Override
    public Collection<FieldInfo> fields() {
        List<FieldInfo> fields = new ArrayList<>();
        for (io.micronaut.inject.ast.FieldElement field : ElementMembers.fieldsOf(element)) {
            fields.add(new ElementFieldInfo(field, new ElementClassInfo(field.getDeclaringType())));
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
