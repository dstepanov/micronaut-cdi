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
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.TypeHierarchyRecord;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Records the generic hierarchy of a class as Micronaut's type hierarchy record of it
 * ({@code VisitorContext.tryRecordTypeHierarchy}), which the container finds at run time with
 * {@code TypeHierarchies.find}: the closure of a bean class, of the class a producer produces, of the class an event
 * can be of and of a class a recorded type names as an argument or a bound.
 *
 * <p>The class is recorded from the element in front of the visitor, every time it is: Micronaut writes the record
 * into the package of that element, writes it once per package however many times it is asked to, leaves out a
 * supertype it cannot name from there - a class that is not public and of another package, or a public one whose
 * arguments name such a class - and says which it left out, and refuses a hierarchy no record can say at all (a
 * variable bounded by a class it cannot name, an inner class of a generic class). Nothing is remembered between two
 * classes.</p>
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
     * @return Whether the record says the whole closure of the class, as Micronaut reports it as it writes the record:
     *     {@code false} where no record can say the hierarchy, or where the record leaves a supertype out
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
        return context.tryRecordTypeHierarchy(component, origin).map(TypeHierarchyRecord::isComplete).orElse(false);
    }
}
