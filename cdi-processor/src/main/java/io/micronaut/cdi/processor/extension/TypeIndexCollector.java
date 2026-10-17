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

import io.micronaut.cdi.processor.visitor.BeanTypesVisitor;
import io.micronaut.cdi.processor.visitor.TypeHierarchyRecords;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the generic hierarchies of the classes an event can be of, and of the classes a recorded type names, as
 * Micronaut's type hierarchy records ({@link TypeHierarchyRecords}), which the runtime finds by class.
 *
 * <p>An event is matched by the types of its class's closure - an {@code OrderCreated} that implements
 * {@code DomainEvent<Order>} is observed by an observer of {@code DomainEvent<Order>} and not by one of
 * {@code DomainEvent<Invoice>} - and the class of an event is any class at all, not a bean with a definition
 * to record it on. So the hierarchy of each class that has a parameterized type above it, or declares type
 * variables of its own, is recorded as it is visited; and so is the hierarchy of each class such a class names as
 * the type arguments and the bounds of its supertypes, of the types it observes and of the types it produces,
 * a class of a library or of the JDK among them, since a bound is compared with the closure of the class an
 * argument names.</p>
 *
 * <p>Micronaut places a record where the classes it refers to can be referred to. A class the compilation does not
 * compile - a class of a library the discovery phase scanned, a class a recorded type names - is recorded from a
 * class of the compilation, and not at all where its record would have to be written into the library's package,
 * nothing the compilation writes belonging there.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class TypeIndexCollector {

    private static final int MAX_REFERENCE_DEPTH = 8;

    private final java.util.Set<String> seen = new java.util.HashSet<>();
    private final java.util.Set<String> ownedSeen = new java.util.HashSet<>();
    /**
     * The classes a recorded type named, which are recorded for that.
     */
    private final java.util.Set<String> referenced = new java.util.HashSet<>();
    private final java.util.Set<String> referencedVariables = new java.util.HashSet<>();

    /**
     * Records what the class says of the types above it, where it says anything a raw class does not, and of the
     * classes it names as the type arguments and the bounds of its supertypes, of the types it observes and of the
     * types it produces.
     *
     * @param element The class
     * @param owned   Whether the compilation compiles it, rather than reading it from a library
     * @param origin  The class of the compilation a record is made from, where the class is not one; {@code null}
     *                where the compilation has none
     * @param context The visitor context
     */
    void collect(ClassElement element, boolean owned, @Nullable ClassElement origin, VisitorContext context) {
        ClassElement from = owned ? element : origin;
        if (from == null || !seen.add(element.getName())) {
            return;
        }
        if (owned) {
            ownedSeen.add(element.getName());
        }
        // an interface, an abstract class and an annotation literal are never the class of an event object: they are
        // recorded where a recorded type names them, which is where their closure is compared
        if ((!element.isInterface() && !element.isAbstract() && !isAnnotationLiteral(element))
            || referenced.contains(element.getName())) {
            record(element, owned, from, context);
        }
        referencesOf(element, from, context);
        // a class nested in another is a class of the compilation like any other, and is not visited on its own
        // unless something is written on it
        for (ClassElement nested : element.getEnclosedElements(
            io.micronaut.inject.ast.ElementQuery.of(ClassElement.class).onlyDeclared())) {
            if (nested.getName().startsWith(element.getName() + "$")) {
                collect(nested, owned, origin, context);
            }
        }
    }

    private static boolean isAnnotationLiteral(ClassElement element) {
        return element.isAssignable("jakarta.enterprise.util.AnnotationLiteral");
    }

    /**
     * Records the classes the class names as the type arguments and the bounds of its supertypes, of the types its
     * observers observe and of the types its producers produce: a bound is compared with the closure of the class an
     * argument names - an {@code Integer} is within {@code ? extends Comparable<Integer>} because it is a
     * {@code Comparable<Integer>} - and the class of a library or of the JDK is recorded for that alone.
     */
    private void referencesOf(ClassElement element, ClassElement origin, VisitorContext context) {
        List<ClassElement> named = new ArrayList<>();
        try {
            BeanTypesVisitor.walkClosure(element, (type, bindings, erased) -> {
                if (!erased) {
                    named.addAll(type.getTypeArguments().values());
                }
            });
            for (io.micronaut.inject.ast.MethodElement method
                : element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS.onlyDeclared())) {
                if (method.hasDeclaredAnnotation(io.micronaut.cdi.processor.Cdi.PRODUCES)) {
                    named.addAll(argumentsOf(method.getGenericReturnType()));
                }
                for (io.micronaut.inject.ast.ParameterElement parameter : method.getParameters()) {
                    if (parameter.hasDeclaredAnnotation(io.micronaut.cdi.processor.Cdi.OBSERVES)
                        || parameter.hasDeclaredAnnotation(io.micronaut.cdi.processor.Cdi.OBSERVES_ASYNC)) {
                        named.addAll(argumentsOf(parameter.getGenericType()));
                    }
                }
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own
            return;
        }
        for (ClassElement type : named) {
            reference(type, 0, origin, context);
        }
    }

    /**
     * The type arguments of a type, of the component of an array: what a bound is compared against.
     */
    private static java.util.Collection<ClassElement> argumentsOf(ClassElement type) {
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        return component.getTypeArguments().values();
    }

    /**
     * Records a class a recorded type names, with the classes its own arguments and bounds name in turn.
     */
    private void reference(ClassElement type, int depth, ClassElement origin, VisitorContext context) {
        if (depth > MAX_REFERENCE_DEPTH) {
            return;
        }
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component instanceof io.micronaut.inject.ast.WildcardElement wildcard) {
            wildcard.getUpperBounds().forEach(bound -> reference(bound, depth + 1, origin, context));
            wildcard.getLowerBounds().forEach(bound -> reference(bound, depth + 1, origin, context));
            return;
        }
        if (component instanceof GenericPlaceholderElement placeholder) {
            if (referencedVariables.add(placeholder.getVariableName() + "@" + depth)) {
                placeholder.getBounds().forEach(bound -> reference(bound, depth + 1, origin, context));
            }
            return;
        }
        if (component.isPrimitive() || "java.lang.Object".equals(component.getName())) {
            return;
        }
        component.getTypeArguments().values().forEach(argument -> reference(argument, depth + 1, origin, context));
        if (!referenced.add(component.getName())) {
            return;
        }
        // the class as it declares itself, over its own variables, rather than as the type that names it gives them
        ClassElement declaration = context.getClassElement(component.getName()).orElse(null);
        if (declaration == null) {
            return;
        }
        // a class the compilation compiles is recorded from itself, and one of a library from the class naming it; a
        // class of the compilation not visited yet is recorded from itself when it is
        boolean owned = ownedSeen.contains(declaration.getName());
        record(declaration, owned, owned ? declaration : origin, context);
        try {
            BeanTypesVisitor.walkClosure(declaration, (supertype, bindings, erased) -> {
                if (!erased && supertype != declaration) {
                    supertype.getTypeArguments().values()
                        .forEach(argument -> reference(argument, depth + 1, origin, context));
                }
            });
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is left to the compiler to report
        }
    }

    /**
     * Records the hierarchy of a class that says more of the types above it than its raw class does: it declares
     * type variables, or a type above it is parameterized.
     */
    private static void record(ClassElement element, boolean owned, ClassElement origin, VisitorContext context) {
        if (element.isPrivate() || element.getPackageName().isEmpty()) {
            return;
        }
        boolean generic;
        try {
            generic = !element.getDeclaredGenericPlaceholders().isEmpty();
            if (!generic) {
                for (ClassElement supertype : element.getAllSuperTypes()) {
                    if (!supertype.getName().equals(element.getName()) && !supertype.isRawType()
                        && !supertype.getTypeArguments().isEmpty()) {
                        generic = true;
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own
            return;
        }
        if (generic) {
            TypeHierarchyRecords.record(element, origin, context);
        }
    }

    /**
     * The classes the records of a compilation were registered from, by round: Micronaut names a definition
     * registered from a class after it, numbered in the order of the round, so one registered in a later round from
     * the same class would take the name of one an earlier round wrote.
     */
    static final class RecordOrigins {
        private final java.util.Set<String> earlier = new java.util.HashSet<>();
        private final java.util.Set<String> current = new java.util.HashSet<>();

        /**
         * The first of the classes no record of an earlier round was registered from.
         *
         * @param classes The classes
         * @return The class, or {@code null} where there is none
         */
        @Nullable ClassElement available(List<? extends ClassElement> classes) {
            for (ClassElement candidate : classes) {
                if (!earlier.contains(candidate.getName())) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Notes that a record of this round is registered from the class.
         *
         * @param origin The class
         */
        void use(ClassElement origin) {
            current.add(origin.getName());
        }

        /**
         * Ends the round: the classes records were registered from are no origins of the next round's.
         */
        void roundFinished() {
            earlier.addAll(current);
            current.clear();
        }
    }
}
