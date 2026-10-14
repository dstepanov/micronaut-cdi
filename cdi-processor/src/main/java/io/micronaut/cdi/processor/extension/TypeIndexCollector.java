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

    private final Map<String, List<Recorded>> byPackage = new LinkedHashMap<>();
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    /**
     * Gathers what the class says of the types above it, where it says anything a raw class does not.
     *
     * @param element The class
     * @param owned   Whether the compilation compiles it, rather than reading it from a library
     */
    void collect(ClassElement element, boolean owned) {
        if (!seen.add(element.getName())) {
            return;
        }
        collectOne(element, owned);
        // a class nested in another is a class of the compilation like any other, and is not visited on its own
        // unless something is written on it
        for (ClassElement nested : element.getEnclosedElements(
            io.micronaut.inject.ast.ElementQuery.of(ClassElement.class).onlyDeclared())) {
            if (nested.getName().startsWith(element.getName() + "$")) {
                collect(nested, owned);
            }
        }
    }

    private void collectOne(ClassElement element, boolean owned) {
        if (element instanceof AnnotationElement || element.isPrivate() || element.getPackageName().isEmpty()) {
            return;
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
            .add(new Recorded(element, entry, owned, owned || refersToPublicClassesAlone(element)));
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
     * <p>The record of a package the compilation compiles classes of is registered from the first of them, which
     * places its definition in the package, with the classes it records as the other origins. What was gathered
     * of a package of a library that refers to public classes alone is registered from a class of the compilation.
     * Micronaut numbers the definitions registered from one class in the order of a round, so a class another round
     * registered a record from is not the origin of one again.</p>
     *
     * @param context The visitor context
     * @param origins The records registered so far, by the name of the class each was registered from, where the
     *                ones of this round are added
     * @param classes The classes the compilation compiles, which a record of a library is registered from
     */
    void write(VisitorContext context, RecordOrigins origins, List<ClassElement> classes) {
        ClassElement first = origins.available(classes);
        if (byPackage.isEmpty() || first == null) {
            byPackage.clear();
            return;
        }
        ClassElement record = context.getClassElement(CdiDeploymentRecord.class.getName()).orElse(null);
        if (record == null) {
            context.fail("The generic hierarchies of the compiled classes cannot be recorded: "
                + CdiDeploymentRecord.class.getName() + ", of micronaut-cdi, is not on the classpath of the "
                + "compilation", first);
            byPackage.clear();
            return;
        }
        List<Recorded> foreign = new ArrayList<>();
        byPackage.forEach((packageName, gathered) -> {
            // a bean of the specification is described by its definition, which records its type closure
            // (CdiBeanTypes), the class over its own variables first: the index holds what no definition describes.
            // Whether a class is one is known once every visitor of the round has seen it
            List<Recorded> entries = new ArrayList<>(gathered.size());
            for (Recorded recorded : gathered) {
                if (!isDescribedByItsDefinition(recorded.element())) {
                    entries.add(recorded);
                }
            }
            if (entries.isEmpty()) {
                return;
            }
            List<ClassElement> owned = new ArrayList<>(entries.size());
            for (Recorded recorded : entries) {
                if (recorded.owned()) {
                    owned.add(recorded.element());
                }
            }
            ClassElement origin = origins.available(owned);
            if (origin == null) {
                if (!owned.isEmpty()) {
                    // every class of the package was the origin of a record of an earlier round, which cannot be
                    // the case of a class first gathered in this one
                    throw new IllegalStateException("No class of " + packageName + " can be the origin of its record");
                }
                for (Recorded recorded : entries) {
                    if (recorded.placeable()) {
                        foreign.add(recorded);
                    }
                }
                return;
            }
            origins.use(origin);
            register(context, record, origin, entries);
        });
        if (!foreign.isEmpty()) {
            origins.use(first);
            register(context, record, first, foreign);
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
