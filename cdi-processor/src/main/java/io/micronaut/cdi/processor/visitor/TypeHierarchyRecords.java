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
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.PackageElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.TypeHierarchyWriter;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Records the generic hierarchy of a class as Micronaut's type hierarchy record of it
 * ({@code VisitorContext.importTypeHierarchy}), which the container finds at run time with
 * {@code TypeHierarchies.find}: the closure of a bean class, of the class a producer produces, of the class an event
 * can be of and of a class a recorded type names as an argument or a bound.
 *
 * <p>Micronaut writes the record into the package of the origin, or into the package of the class where the class,
 * a bound or a supertype of it is a class of that package that is not public, and leaves out a supertype no record
 * could name: a class that is not public and of a third package. A class whose record would leave a supertype out
 * is not recorded, so that the container never reads a closure with a type missing from it, and neither is a class
 * of a library whose record would have to be written into the library's package, nothing the compilation writes
 * belonging there: a package of the JDK among them. The caller keeps what the record cannot say.</p>
 *
 * <p>A class is recorded once per visitor context, from the first origin it is recorded with.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class TypeHierarchyRecords {

    private static final Map<VisitorContext, Map<String, Boolean>> RECORDED = new java.util.WeakHashMap<>();
    private static final int MAX_DEPTH = 16;

    private TypeHierarchyRecords() {
    }

    /**
     * Records the hierarchy of the class of a type, where the record can say all of it.
     *
     * @param type    The type: a class, a parameterization of one, or an array of one, whose class is recorded
     * @param origin  The class or the member of the compilation the record is made for
     * @param owned   Whether the compilation compiles the class, rather than reading it from a library
     * @param context The visitor context
     * @return Whether the class has a record that says its whole closure
     */
    public static boolean record(ClassElement type, Element origin, boolean owned, VisitorContext context) {
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component instanceof GenericPlaceholderElement || component instanceof WildcardElement
            || component.isPrimitive() || component instanceof AnnotationElement
            || "java.lang.Object".equals(component.getName())) {
            return false;
        }
        Map<String, Boolean> recorded = recorded(context);
        Boolean known = recorded.get(component.getName());
        if (known != null) {
            return known;
        }
        boolean answered;
        try {
            // the class as it declares itself, over its own variables, rather than as the type that names it gives them
            ClassElement declaration = component.getTypeArguments().isEmpty() ? component
                : context.getClassElement(component.getName()).orElse(component);
            answered = recordable(declaration, origin, owned);
            if (answered) {
                context.importTypeHierarchy(declaration, origin);
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve, or one Micronaut refuses to record: nothing is recorded, and
            // the container reads what it can without it
            answered = false;
        }
        if (answered || owned) {
            // a class of the compilation recorded from a class naming it before it was visited itself, which a
            // record from that class's package could not say, is recorded again from itself
            recorded.put(component.getName(), answered);
        }
        return answered;
    }

    /**
     * What was recorded in the compilation of the context. Kept by the context itself rather than among its
     * attributes, which the Groovy compiler shares between every compilation in the process.
     */
    private static Map<String, Boolean> recorded(VisitorContext context) {
        synchronized (RECORDED) {
            return RECORDED.computeIfAbsent(context, ignored -> new HashMap<>());
        }
    }

    /**
     * Whether the record of the class says its whole closure, and is written into a package of the compilation.
     */
    private static boolean recordable(ClassElement declaration, Element origin, boolean owned) {
        if (declaration.isPrivate() || declaration.getPackageName().isEmpty()) {
            return false;
        }
        String typePackage = declaration.getPackageName();
        String originPackage = packageOf(origin);
        // the package Micronaut writes the record into: that of the class, or that of the origin, where a record of a
        // class of another package goes into a sub-package named after the class's
        boolean intoTypePackage = TypeHierarchyWriter.recordClassName(declaration, origin)
            .equals(TypeHierarchies.recordClassName(declaration.getName(), typePackage));
        String recordPackage = intoTypePackage ? typePackage : originPackage;
        if (recordPackage == null || !owned && !recordPackage.equals(originPackage)) {
            // into the package of a class of a library, which is no package of the compilation
            return false;
        }
        for (GenericPlaceholderElement variable : declaration.getDeclaredGenericPlaceholders()) {
            if (!isNameableFrom(variable, recordPackage)) {
                return false;
            }
        }
        for (ClassElement superType : declaration.getAllSuperTypes()) {
            if (!superType.getName().equals(declaration.getName()) && !isNameableFrom(superType, recordPackage)) {
                // left out of the record
                return false;
            }
        }
        return true;
    }

    private static @Nullable String packageOf(Element origin) {
        if (origin instanceof ClassElement classElement) {
            return classElement.getPackageName();
        }
        if (origin instanceof MemberElement member) {
            return member.getDeclaringType().getPackageName();
        }
        if (origin instanceof PackageElement packageElement) {
            return packageElement.getName();
        }
        return null;
    }

    /**
     * Whether every class a type names - itself, its arguments, and the bounds of its variables and wildcards - can be
     * named from a package: it is public, or of that package.
     */
    private static boolean isNameableFrom(ClassElement type, String packageName) {
        return isNameableFrom(type, packageName, new HashSet<>(), 0);
    }

    private static boolean isNameableFrom(ClassElement type, String packageName, Set<String> visited, int depth) {
        if (depth > MAX_DEPTH) {
            return true;
        }
        if (type instanceof WildcardElement wildcard) {
            return allNameableFrom(wildcard.getUpperBounds(), packageName, visited, depth)
                && allNameableFrom(wildcard.getLowerBounds(), packageName, visited, depth);
        }
        if (type instanceof GenericPlaceholderElement placeholder) {
            // the bounds of a variable within its own bounds are not visited again
            return !visited.add("<" + placeholder.getVariableName() + ">")
                || allNameableFrom(placeholder.getBounds(), packageName, visited, depth);
        }
        ClassElement component = type;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component.isPrimitive()) {
            return true;
        }
        if (!isPublic(component) && !component.getPackageName().equals(packageName)) {
            return false;
        }
        if (!visited.add(component.getName())) {
            return true;
        }
        return allNameableFrom(component.getTypeArguments().values(), packageName, visited, depth + 1);
    }

    private static boolean allNameableFrom(Collection<? extends ClassElement> types, String packageName,
                                           Set<String> visited, int depth) {
        for (ClassElement type : types) {
            if (!isNameableFrom(type, packageName, visited, depth)) {
                return false;
            }
        }
        return true;
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
