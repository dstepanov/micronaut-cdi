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

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>Every record is made from the class being visited, and nothing is kept from one class to the next: a class two
 * classes of the compilation name is recorded from each, into the package of each, and Micronaut writes one record
 * per package and finds one of them at run time.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class TypeHierarchyVisitor implements TypeElementVisitor<Object, Object> {

    private static final String GENERATED = "io.micronaut.cdi.generated.";
    private static final int MAX_REFERENCE_DEPTH = 8;

    @Override
    public TypeElementQuery query() {
        return TypeElementQuery.onlyClass();
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        // beside the bean types: neither reads what the other writes
        return LOWEST_PRECEDENCE - 360;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.getName().startsWith(GENERATED)) {
            return;
        }
        collect(element, new HashSet<>(), context);
    }

    /**
     * Records what the class says of the types above it, where it says anything a raw class does not, and of the
     * classes it names as the type arguments and the bounds of its supertypes, of the types it observes and of the
     * types it produces; and the same of the classes nested in it.
     *
     * @param element  The class, which every record is made from
     * @param recorded The classes recorded from the class visited so far, which are not recorded again from it
     * @param context  The visitor context
     */
    private static void collect(ClassElement element, Set<String> recorded, VisitorContext context) {
        // an interface, an abstract class and an annotation literal are never the class of an event object: they are
        // recorded where a recorded type names them, which is where their closure is compared
        if (!element.isInterface() && !element.isAbstract() && !isAnnotationLiteral(element)) {
            record(element, element, recorded, context);
        }
        referencesOf(element, recorded, context);
        // a class nested in another is a class of the compilation like any other, and is not visited on its own
        // unless something is written on it
        for (ClassElement nested : element.getEnclosedElements(ElementQuery.of(ClassElement.class).onlyDeclared())) {
            if (nested.getName().startsWith(element.getName() + "$")) {
                collect(nested, recorded, context);
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
    private static void referencesOf(ClassElement element, Set<String> recorded, VisitorContext context) {
        List<ClassElement> named = new ArrayList<>();
        try {
            for (ClassElement type : BeanTypesVisitor.closureOf(element)) {
                if (!type.isRawType()) {
                    named.addAll(type.getTypeArguments().values());
                }
            }
            for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
                if (method.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                    named.addAll(argumentsOf(method.getGenericReturnType()));
                }
                for (ParameterElement parameter : method.getParameters()) {
                    if (parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                        || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)) {
                        named.addAll(argumentsOf(parameter.getGenericType()));
                    }
                }
            }
            for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())) {
                if (field.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                    named.addAll(argumentsOf(field.getGenericType()));
                }
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own
            return;
        }
        Set<String> variables = new HashSet<>();
        for (ClassElement type : named) {
            reference(type, 0, element, recorded, variables, context);
        }
    }

    /**
     * The type arguments of a type, of the component of an array: what a bound is compared against.
     */
    private static Collection<ClassElement> argumentsOf(ClassElement type) {
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        return component.getTypeArguments().values();
    }

    /**
     * Records a class a recorded type names, with the classes its own arguments and bounds name in turn.
     */
    private static void reference(ClassElement type, int depth, ClassElement origin, Set<String> recorded,
                                  Set<String> variables, VisitorContext context) {
        if (depth > MAX_REFERENCE_DEPTH) {
            return;
        }
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component instanceof WildcardElement wildcard) {
            wildcard.getUpperBounds().forEach(bound -> reference(bound, depth + 1, origin, recorded, variables, context));
            wildcard.getLowerBounds().forEach(bound -> reference(bound, depth + 1, origin, recorded, variables, context));
            return;
        }
        if (component instanceof GenericPlaceholderElement placeholder) {
            if (variables.add(placeholder.getVariableName() + "@" + depth)) {
                placeholder.getBounds().forEach(bound -> reference(bound, depth + 1, origin, recorded, variables, context));
            }
            return;
        }
        if (component.isPrimitive() || "java.lang.Object".equals(component.getName())) {
            return;
        }
        component.getTypeArguments().values()
            .forEach(argument -> reference(argument, depth + 1, origin, recorded, variables, context));
        if (recorded.contains(component.getName())) {
            return;
        }
        // the class as it declares itself, over its own variables, rather than as the type that names it gives them
        ClassElement declaration = context.getClassElement(component.getName()).orElse(null);
        if (declaration == null) {
            return;
        }
        record(declaration, origin, recorded, context);
        try {
            for (ClassElement supertype : BeanTypesVisitor.closureOf(declaration)) {
                if (supertype != declaration && !supertype.isRawType()) {
                    supertype.getTypeArguments().values()
                        .forEach(argument -> reference(argument, depth + 1, origin, recorded, variables, context));
                }
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is left to the compiler to report
        }
    }

    /**
     * Records the hierarchy of a class that says more of the types above it than its raw class does: it declares
     * type variables, or a type above it is parameterized.
     */
    private static void record(ClassElement element, ClassElement origin, Set<String> recorded,
                               VisitorContext context) {
        if (!recorded.add(element.getName()) || element.isPrivate() || element.getPackageName().isEmpty()) {
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
}
