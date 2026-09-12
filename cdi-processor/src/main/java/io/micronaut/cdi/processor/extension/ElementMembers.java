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
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The members a class has, as the language model counts them: everything the class itself declares, everything
 * its superclasses declare up to and excluding {@code java.lang.Object}, and everything every direct and indirect
 * superinterface declares.
 *
 * <p>Micronaut's own queries answer a different question — the members a bean has, with what a subclass overrides
 * or hides reduced to the one declaration that wins. The language model keeps every declaration and lets an
 * extension tell them apart by the class that declared each, so the hierarchy is walked here and each class is
 * asked only about what it declares itself.</p>
 *
 * <p>Each class is asked through its raw element. A superclass reached from a subclass carries the type arguments
 * the subclass bound it with, and a member read through that has its type variables substituted away: the
 * language model reports a superclass method with the variables its own declaration wrote.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementMembers {

    private static final String OBJECT = "java.lang.Object";

    private static final ElementQuery<MethodElement> METHODS =
        ElementQuery.ALL_METHODS.onlyDeclared().includeOverriddenMethods().includeHiddenElements();

    private static final ElementQuery<FieldElement> FIELDS =
        ElementQuery.ALL_FIELDS.onlyDeclared().includeHiddenElements().includeEnumConstants();

    private ElementMembers() {
    }

    /**
     * Every method and constructor declared in the class, in its superclasses and in its superinterfaces.
     *
     * @param clazz The class
     * @return The methods
     */
    static List<MethodElement> methodsOf(ClassElement clazz) {
        return declaredIn(clazz, METHODS);
    }

    /**
     * Every field declared in the class, in its superclasses and in its superinterfaces.
     *
     * @param clazz The class
     * @return The fields
     */
    static List<FieldElement> fieldsOf(ClassElement clazz) {
        return declaredIn(clazz, FIELDS);
    }

    private static <T extends Element> List<T> declaredIn(ClassElement clazz, ElementQuery<T> query) {
        // the class and its superclasses; java.lang.Object contributes nothing unless it is the class asked about
        List<ClassElement> classes = new ArrayList<>();
        ClassElement type = clazz;
        while (type != null) {
            classes.add(type);
            type = type.getSuperType().filter(superClass -> !OBJECT.equals(superClass.getName())).orElse(null);
        }

        List<T> members = new ArrayList<>();
        Set<String> interfaces = new LinkedHashSet<>();
        for (ClassElement each : classes) {
            members.addAll(each.getRawClassElement().getEnclosedElements(query));
            collectInterfaces(each, interfaces, members, query);
        }
        return members;
    }

    private static <T extends Element> void collectInterfaces(ClassElement type, Set<String> seen, List<T> members,
                                                             ElementQuery<T> query) {
        for (ClassElement superInterface : type.getInterfaces()) {
            // an interface reached along two paths of the hierarchy declares its members once
            if (seen.add(superInterface.getName())) {
                members.addAll(superInterface.getRawClassElement().getEnclosedElements(query));
                collectInterfaces(superInterface, seen, members, query);
            }
        }
    }
}
