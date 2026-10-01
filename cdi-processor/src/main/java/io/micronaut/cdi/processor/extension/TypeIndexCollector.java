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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gathers the generic hierarchy of every class a compilation compiles, and writes it where the runtime reads
 * it: on a class generated in the package of the classes, as annotation values.
 *
 * <p>An event is matched by the types of its class's closure - an {@code OrderCreated} that implements
 * {@code DomainEvent<Order>} is observed by an observer of {@code DomainEvent<Order>} and not by one of
 * {@code DomainEvent<Invoice>} - and the class of an event is any class at all, not a bean with a definition
 * to record it on. So the closure of each class that has a parameterized type above it, or declares type
 * variables of its own, is recorded by its name, once the classes of the compilation have all come past. The
 * record is written beside the classes, in their own package, so that it can refer to the ones that are not
 * public.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class TypeIndexCollector {

    private static final int ENTRIES_PER_CLASS = 25;
    private static final String PREFIX = "CdiTypeIndex";

    /**
     * The records of the generated classes, from the moment a class is generated until the compiler comes to
     * it, which a compiler is free to do with another visitor.
     */
    private static final Map<String, AnnotationValue<CdiTypeIndex>> RECORDS = new ConcurrentHashMap<>();

    private final Map<String, List<AnnotationValue<CdiTypeEntry>>> byPackage = new LinkedHashMap<>();
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    /**
     * Whether nothing has been gathered.
     */
    boolean isEmpty() {
        return byPackage.isEmpty();
    }

    /**
     * Gathers what the class says of the types above it, where it says anything a raw class does not.
     */
    void collect(ClassElement element) {
        if (!seen.add(element.getName())) {
            return;
        }
        collectOne(element);
        // a class nested in another is a class of the compilation like any other, and is not visited on its own
        // unless something is written on it
        for (ClassElement nested : element.getEnclosedElements(
            io.micronaut.inject.ast.ElementQuery.of(ClassElement.class).onlyDeclared())) {
            if (nested.getName().startsWith(element.getName() + "$")) {
                collect(nested);
            }
        }
    }

    private void collectOne(ClassElement element) {
        if (element instanceof AnnotationElement || element.isPrivate() || element.getPackageName().isEmpty()
            || element.getSimpleName().startsWith(PREFIX)) {
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
        byPackage.computeIfAbsent(element.getPackageName(), name -> new ArrayList<>())
            .add(AnnotationValue.builder(CdiTypeEntry.class)
                .member("name", element.getName())
                .member("variables", variables.toArray(new String[0]))
                .member("supertypes", supertypes.toArray(new AnnotationValue<?>[0]))
                .build());
    }

    /**
     * Writes a class into each package that has something recorded, in the language being compiled: an empty
     * class, whose record is put on it as annotation values when the compiler comes to it.
     */
    void write(VisitorContext context, String suffix) {
        boolean kotlin = context.getLanguage() == VisitorContext.Language.KOTLIN;
        byPackage.forEach((packageName, entries) -> {
            for (int from = 0, part = 0; from < entries.size(); from += ENTRIES_PER_CLASS, part++) {
                List<AnnotationValue<CdiTypeEntry>> chunk =
                    entries.subList(from, Math.min(entries.size(), from + ENTRIES_PER_CLASS));
                String className = PREFIX + suffix + (part == 0 ? "" : "_" + part);
                String source = "package " + packageName + (kotlin ? "" : ";") + "\n\n"
                    + "@jakarta.inject.Singleton\n"
                    + "@io.micronaut.cdi.internal.metadata.CdiExtensionComponents\n"
                    + (kotlin ? "class " + className + "\n" : "final class " + className + " {\n}\n");
                RECORDS.put(packageName + "." + className, AnnotationValue.builder(CdiTypeIndex.class)
                    .member("value", chunk.toArray(new AnnotationValue<?>[0])).build());
                context.visitGeneratedSourceFile(packageName, className).ifPresent(file -> {
                    try {
                        file.write(writer -> writer.write(source));
                    } catch (Exception e) {
                        throw new IllegalStateException("The record of the generic hierarchies of " + packageName
                            + " could not be written", e);
                    }
                });
            }
        });
        byPackage.clear();
    }

    /**
     * Puts its record on a generated class the compiler has come to.
     *
     * @return Whether the class is one of the generated ones
     */
    static boolean recordOn(ClassElement element) {
        if (!element.getSimpleName().startsWith(PREFIX)) {
            return false;
        }
        AnnotationValue<CdiTypeIndex> record = RECORDS.remove(element.getName());
        if (record == null) {
            return false;
        }
        element.annotate(record);
        return true;
    }
}
