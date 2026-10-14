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

import io.micronaut.cdi.internal.metadata.CdiDeploymentRecord;
import io.micronaut.cdi.internal.metadata.CdiExtensionComponents;
import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiTypeEntry;
import io.micronaut.cdi.internal.metadata.CdiTypeIndex;
import io.micronaut.cdi.processor.visitor.BeanTypesVisitor;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers the generic hierarchy of the classes a compilation compiles, and records it where the runtime reads it:
 * on definitions of a {@link CdiDeploymentRecord} the compilation registers, as annotation values.
 *
 * <p>An event is matched by the types of its class's closure - an {@code OrderCreated} that implements
 * {@code DomainEvent<Order>} is observed by an observer of {@code DomainEvent<Order>} and not by one of
 * {@code DomainEvent<Invoice>} - and the class of an event is any class at all, not a bean with a definition
 * to record it on. So the closure of each class that has a parameterized type above it, or declares type
 * variables of its own, is recorded by its name, as each round of the compilation finishes.</p>
 *
 * <p>No source is generated for the record: it is registered with Micronaut's {@code VisitorContext.registerBean}
 * as a bean of no use beyond its metadata, in chunks a static initializer can hold. A record refers to the classes
 * above a class by their class literals, and a class that is not public can only be referred to from its own
 * package: the record of a package is registered from a class of the package, which places its definition there.
 * A class the compilation does not compile - a class of a library the discovery phase scanned - is recorded from
 * the compilation's own classes where everything its record refers to is public, and not at all otherwise, since
 * nothing the compilation writes may be placed in a package of the library.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class TypeIndexCollector {

    private static final int ENTRIES_PER_RECORD = 25;
    private static final int MAX_REFERENCE_DEPTH = 8;

    private final Map<String, List<Recorded>> byPackage = new LinkedHashMap<>();

    private final java.util.Set<String> seen = new java.util.HashSet<>();
    private final java.util.Set<String> ownedSeen = new java.util.HashSet<>();
    /**
     * The classes a recorded type named, which are recorded for that.
     */
    private final java.util.Set<String> referenced = new java.util.HashSet<>();
    private final java.util.Set<String> referencedVariables = new java.util.HashSet<>();
    /**
     * The classes a record was made of, and whether as a class the compilation compiles: one recorded as a class a
     * type named is recorded again as its own once the compilation is seen to compile it.
     */
    private final Map<String, Boolean> recordedNames = new java.util.HashMap<>();

    /**
     * Gathers what the class says of the types above it, where it says anything a raw class does not, and of the
     * classes it names as the type arguments and the bounds of its supertypes, of the types it observes and of the
     * types it produces.
     *
     * @param element The class
     * @param owned   Whether the compilation compiles it, rather than reading it from a library
     */
    void collect(ClassElement element, boolean owned, VisitorContext context) {
        if (!seen.add(element.getName())) {
            return;
        }
        if (owned) {
            ownedSeen.add(element.getName());
        }
        // an interface, an abstract class and an annotation literal are never the class of an event object: they are
        // recorded where a recorded type names them, which is where their closure is compared
        if ((!element.isInterface() && !element.isAbstract() && !isAnnotationLiteral(element))
            || referenced.contains(element.getName())) {
            record(element, owned);
        }
        referencesOf(element, context);
        // a class nested in another is a class of the compilation like any other, and is not visited on its own
        // unless something is written on it
        for (ClassElement nested : element.getEnclosedElements(
            io.micronaut.inject.ast.ElementQuery.of(ClassElement.class).onlyDeclared())) {
            if (nested.getName().startsWith(element.getName() + "$")) {
                collect(nested, owned, context);
            }
        }
    }

    private static boolean isAnnotationLiteral(ClassElement element) {
        return element.isAssignable("jakarta.enterprise.util.AnnotationLiteral");
    }

    /**
     * Gathers the classes the class names as the type arguments and the bounds of its supertypes, of the types its
     * observers observe and of the types its producers produce: a bound is compared with the closure of the class an
     * argument names - an {@code Integer} is within {@code ? extends Comparable<Integer>} because it is a
     * {@code Comparable<Integer>} - and the class of a library or of the JDK is recorded for that alone.
     */
    private void referencesOf(ClassElement element, VisitorContext context) {
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
            reference(type, 0, context);
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
    private void reference(ClassElement type, int depth, VisitorContext context) {
        if (depth > MAX_REFERENCE_DEPTH) {
            return;
        }
        ClassElement unwrapped = type;
        while (unwrapped.isArray()) {
            unwrapped = unwrapped.fromArray();
        }
        ClassElement component = unwrapped;
        if (component instanceof io.micronaut.inject.ast.WildcardElement wildcard) {
            wildcard.getUpperBounds().forEach(bound -> reference(bound, depth + 1, context));
            wildcard.getLowerBounds().forEach(bound -> reference(bound, depth + 1, context));
            return;
        }
        if (component instanceof GenericPlaceholderElement placeholder) {
            if (referencedVariables.add(placeholder.getVariableName() + "@" + depth)) {
                placeholder.getBounds().forEach(bound -> reference(bound, depth + 1, context));
            }
            return;
        }
        if (component.isPrimitive() || "java.lang.Object".equals(component.getName())) {
            return;
        }
        component.getTypeArguments().values().forEach(argument -> reference(argument, depth + 1, context));
        if (!referenced.add(component.getName())) {
            return;
        }
        // the class as it declares itself, over its own variables, rather than as the type that names it gives them
        ClassElement declaration = context.getClassElement(component.getName()).orElse(null);
        if (declaration == null) {
            return;
        }
        // a class the compilation compiles is recorded as its own when it is visited, which may come later
        record(declaration, seen.contains(component.getName()) && ownedSeen.contains(component.getName()));
        try {
            BeanTypesVisitor.walkClosure(declaration, (supertype, bindings, erased) -> {
                if (!erased && supertype != declaration) {
                    supertype.getTypeArguments().values().forEach(argument -> reference(argument, depth + 1, context));
                }
            });
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is left to the compiler to report
        }
    }

    private void record(ClassElement element, boolean owned) {
        if (element instanceof AnnotationElement || element.isPrivate() || element.getPackageName().isEmpty()) {
            return;
        }
        Boolean recordedAsOwned = recordedNames.get(element.getName());
        if (recordedAsOwned != null && (recordedAsOwned || !owned)) {
            return;
        }
        recordedNames.put(element.getName(), owned);
        if (recordedAsOwned != null) {
            List<Recorded> ofThePackage = byPackage.get(element.getPackageName());
            if (ofThePackage != null) {
                ofThePackage.removeIf(recorded -> recorded.element().getName().equals(element.getName()));
            }
        }
        List<String> variables = new ArrayList<>();
        element.getTypeArguments().forEach((name, argument) -> {
            if (argument instanceof GenericPlaceholderElement) {
                variables.add(name);
            }
        });
        List<AnnotationValue<CdiRecordedType>> closure;
        try {
            closure = BeanTypesVisitor.closureOf(element, false);
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own
            return;
        }
        // the class itself leads its closure, and is what the record is of
        List<AnnotationValue<CdiRecordedType>> supertypes = closure.subList(Math.min(1, closure.size()), closure.size());
        boolean parameterized = false;
        for (AnnotationValue<CdiRecordedType> supertype : supertypes) {
            parameterized |= !supertype.getAnnotations(CdiRecordedType.ARGUMENTS).isEmpty();
        }
        if (variables.isEmpty() && !parameterized) {
            return;
        }
        AnnotationValue<CdiTypeEntry> entry = AnnotationValue.builder(CdiTypeEntry.class)
            .member("name", element.getName())
            .member("variables", variables.toArray(new String[0]))
            .member("supertypes", supertypes.toArray(new AnnotationValue<?>[0]))
            .build();
        byPackage.computeIfAbsent(element.getPackageName(), name -> new ArrayList<>())
            .add(new Recorded(element, entry, owned, refersToPublicClassesAlone(element)));
    }

    /**
     * Whether everything the record of the class refers to - the class, the classes above it and their type
     * arguments - is public, and can be referred to from any package.
     */
    private static boolean refersToPublicClassesAlone(ClassElement element) {
        boolean[] everyPublic = {element.isPublic()};
        BeanTypesVisitor.walkClosure(element, (type, bindings, erased) -> {
            everyPublic[0] &= isPublic(type);
            for (ClassElement argument : type.getTypeArguments().values()) {
                everyPublic[0] &= isPublic(argument);
            }
        });
        return everyPublic[0];
    }

    private static boolean isPublic(ClassElement type) {
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        return component.isPrimitive() || component instanceof GenericPlaceholderElement
            || component instanceof io.micronaut.inject.ast.WildcardElement || component.isPublic();
    }

    /**
     * Registers the records of what was gathered since the last time, and forgets it.
     *
     * <p>What refers to public classes alone is recorded once for the compilation, registered from a class of it.
     * The record of a class that refers to a class only its package can name is registered from a class of that
     * package, which places its definition there, with the other classes of the package that need it. What the
     * compilation does not compile and refers to such a class is not recorded. Micronaut numbers the definitions
     * registered from one class in the order of a round, so a class another round registered a record from is not
     * the origin of one again.</p>
     *
     * @param context The visitor context
     * @param origins The records registered so far, by the name of the class each was registered from, where the
     *                ones of this round are added
     * @param classes The classes the compilation compiles
     */
    void write(VisitorContext context, RecordOrigins origins, List<ClassElement> classes) {
        if (byPackage.isEmpty()) {
            return;
        }
        ClassElement record = context.getClassElement(CdiDeploymentRecord.class.getName()).orElse(null);
        if (record == null) {
            context.fail("The generic hierarchies of the compiled classes cannot be recorded: "
                + CdiDeploymentRecord.class.getName() + ", of micronaut-cdi, is not on the classpath of the "
                + "compilation", classes.isEmpty() ? null : classes.get(0));
            byPackage.clear();
            return;
        }
        List<Recorded> shared = new ArrayList<>();
        List<ClassElement> sharedOwned = new ArrayList<>();
        byPackage.forEach((packageName, gathered) -> {
            List<Recorded> ofThePackage = new ArrayList<>();
            List<ClassElement> owned = new ArrayList<>();
            for (Recorded recorded : gathered) {
                // a bean of the specification is described by its definition, which records its type closure
                // (CdiBeanTypes), the class over its own variables first: the index holds what no definition
                // describes. Whether a class is one is known once every visitor of the round has seen it
                if (isDescribedByItsDefinition(recorded.element())) {
                    continue;
                }
                if (recorded.placeable()) {
                    shared.add(recorded);
                    if (recorded.owned()) {
                        sharedOwned.add(recorded.element());
                    }
                } else if (recorded.owned()) {
                    ofThePackage.add(recorded);
                    owned.add(recorded.element());
                }
            }
            if (ofThePackage.isEmpty()) {
                return;
            }
            ClassElement origin = origins.available(owned);
            if (origin == null) {
                // every class of it was the origin of a record of an earlier round, which a class first gathered in
                // this one cannot have been
                throw new IllegalStateException("No class of " + packageName + " can be the origin of its record");
            }
            origins.use(origin);
            register(context, record, origin, ofThePackage);
        });
        if (!shared.isEmpty()) {
            ClassElement origin = origins.available(sharedOwned);
            if (origin == null) {
                origin = origins.available(classes);
            }
            if (origin != null) {
                origins.use(origin);
                register(context, record, origin, shared);
            }
        }
        byPackage.clear();
    }

    /**
     * Whether the class is a bean of the specification whose definition records its type closure.
     */
    private static boolean isDescribedByItsDefinition(ClassElement element) {
        return !element.isAbstract() && !element.isInterface()
            && element.hasStereotype(io.micronaut.cdi.internal.metadata.CdiScope.class)
            && element.hasAnnotation(io.micronaut.cdi.internal.metadata.CdiBeanTypes.class)
            && !io.micronaut.cdi.processor.visitor.SpecificationBeans.isVetoed(element);
    }

    private static void register(VisitorContext context, ClassElement record, ClassElement origin,
                                 List<Recorded> entries) {
        for (int from = 0; from < entries.size(); from += ENTRIES_PER_RECORD) {
            List<Recorded> chunk = entries.subList(from, Math.min(entries.size(), from + ENTRIES_PER_RECORD));
            List<io.micronaut.inject.ast.Element> origins = new ArrayList<>(chunk.size() + 1);
            origins.add(origin);
            List<AnnotationValue<?>> values = new ArrayList<>(chunk.size());
            for (Recorded recorded : chunk) {
                if (recorded.owned() && recorded.element() != origin) {
                    origins.add(recorded.element());
                }
                values.add(recorded.entry());
            }
            context.registerBean(record, origins.toArray(new io.micronaut.inject.ast.Element[0]))
                .annotate(AnnotationValue.builder(CdiExtensionComponents.class).build())
                .annotate(AnnotationValue.builder(CdiTypeIndex.class)
                    .member("value", values.toArray(new AnnotationValue<?>[0])).build());
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
        @org.jspecify.annotations.Nullable ClassElement available(List<? extends ClassElement> classes) {
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

    /**
     * What was gathered of one class.
     *
     * @param element   The class
     * @param entry     Its record
     * @param owned     Whether the compilation compiles it
     * @param placeable Whether its record may be registered from a class of another package
     */
    private record Recorded(ClassElement element, AnnotationValue<CdiTypeEntry> entry, boolean owned,
                            boolean placeable) {
    }
}
