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

import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Writes the default name onto a bean that asked for one, the way sections 2.1.6 and 2.2.9 spell it.
 *
 * <p>A bean has a name when it declares {@code Named}, or carries it through a stereotype, and the name it has
 * when none was given is spelled out by the specification: the simple class name with its first character made
 * lower case — only the first, whatever follows it — the field name for a producer field, and the method name
 * for a producer method, read as a property name when the method is a getter.</p>
 *
 * <p>The name is written while the bean is compiled rather than derived when it is asked for, so that resolving
 * a bean by name is the same lookup whichever way the name arose.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BeanNameVisitor implements TypeElementVisitor<Object, Object> {

    private static final String NAMED = "jakarta.inject.Named";

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * Runs after the scope of the bean has been decided, since only a bean of the specification is named this
     * way.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 150;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.getAnnotationMetadata().hasStereotype(CdiScope.class)) {
            nameIfAskedFor(element, defaultClassName(element.getSimpleName()));
        }
        element.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
            .filter(method -> method.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(method -> nameIfAskedFor(method, defaultMethodName(method)));
        element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .filter(field -> field.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(field -> nameIfAskedFor(field, field.getName()));
    }

    /**
     * Writes the default name where {@code Named} was declared, or carried by a stereotype, without one.
     */
    private static void nameIfAskedFor(Element element, String defaultName) {
        if (!element.getAnnotationMetadata().hasStereotype(NAMED)) {
            return;
        }
        if (element.getAnnotationMetadata().stringValue(NAMED).filter(name -> !name.isEmpty()).isPresent()) {
            return;
        }
        if (element.hasDeclaredAnnotation(NAMED)) {
            element.annotate(NAMED, builder -> builder.value(defaultName));
            return;
        }
        // the name came through a stereotype: the bean has the name, but not the Named qualifier — writing
        // the jakarta annotation would put Named among the bean's qualifiers, which section 2.6 does not
        element.annotate("io.micronaut.cdi.annotation.CdiName", builder -> builder.value(defaultName));
    }

    /**
     * The default name of a class: its simple name, first character made lower case and nothing else touched.
     */
    private static String defaultClassName(String simpleName) {
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /**
     * The default name of a producer method: the property name when the method is a getter, and the method name
     * itself otherwise.
     */
    private static String defaultMethodName(MethodElement method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return defaultClassName(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
            && method.getReturnType().getName().equals("boolean")) {
            return defaultClassName(name.substring(2));
        }
        return name;
    }
}
