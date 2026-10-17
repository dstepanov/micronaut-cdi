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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.HashSet;
import java.util.Set;

/**
 * Records the generic hierarchy of a class as Micronaut's type hierarchy record of it
 * ({@code VisitorContext.importTypeHierarchy}), which the container finds at run time with
 * {@code TypeHierarchies.find}: the closure of a bean class, of the class a producer produces, of the class an event
 * can be of and of a class a recorded type names as an argument or a bound.
 *
 * <p>The class is recorded from the element in front of the visitor, every time it is: Micronaut writes the record
 * into the package of that element, writes it once per package however many times it is asked to, leaves out a
 * supertype it cannot name from there - naming it in {@code TypeHierarchy.getOmittedSuperTypeNames()} - and refuses a
 * hierarchy no record can say at all (a variable bounded by a class it cannot name, an inner class of a generic
 * class). Nothing is remembered between two classes.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class TypeHierarchyRecords {

    private TypeHierarchyRecords() {
    }

    /**
     * Records the hierarchy of the class of a type, from an element of the compilation.
     *
     * @param type    The type: a class, a parameterization of one, or an array of one, whose class is recorded
     * @param origin  The class or the member of the compilation the record is made from
     * @param context The visitor context
     * @return Whether the record says the whole closure of the class, as far as the names of its supertypes tell:
     *     {@code false} where Micronaut refused the hierarchy, or where a supertype is a class that is not public
     *     and of another package, which the record leaves out
     */
    public static boolean record(ClassElement type, Element origin, VisitorContext context) {
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component instanceof GenericPlaceholderElement || component instanceof WildcardElement
            || component.isPrimitive() || component instanceof AnnotationElement || component.isPrivate()
            || "java.lang.Object".equals(component.getName())) {
            return false;
        }
        try {
            context.importTypeHierarchy(component, origin);
        } catch (ProcessingException e) {
            // a hierarchy no record can say: the caller keeps what it needs of it
            return false;
        }
        return !omitsASupertype(component, origin);
    }

    /**
     * Whether the record of the class leaves a supertype out, as far as the names of the supertypes tell: one that is
     * not public, or is nested in a class that is not, and is of another package than the record. A supertype whose
     * arguments name such a class is left out as well, and is not looked for here.
     */
    private static boolean omitsASupertype(ClassElement type, Element origin) {
        String recordPackage = isPublic(type) ? packageOf(origin) : type.getPackageName();
        for (ClassElement superType : type.getAllSuperTypes()) {
            if (!superType.getName().equals(type.getName()) && !isPublic(superType)
                && !superType.getPackageName().equals(recordPackage)) {
                return true;
            }
        }
        return false;
    }

    private static String packageOf(Element origin) {
        if (origin instanceof ClassElement classElement) {
            return classElement.getPackageName();
        }
        if (origin instanceof MemberElement member) {
            return member.getDeclaringType().getPackageName();
        }
        return origin.getName();
    }

    /**
     * Whether a class can be named from any package: it and every class enclosing it are public.
     */
    private static boolean isPublic(ClassElement type) {
        Set<String> visited = new HashSet<>();
        for (ClassElement current = type; current != null && visited.add(current.getName());
             current = current.getEnclosingType().orElse(null)) {
            if (!current.isPublic()) {
                return false;
            }
        }
        return true;
    }
}
