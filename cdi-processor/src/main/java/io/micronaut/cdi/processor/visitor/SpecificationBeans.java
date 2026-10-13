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

import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Tells a class this processor compiles as a bean of the specification apart from a plain Micronaut class compiled
 * alongside it, which keeps Micronaut's own rules, and answers what the visitors - and the phases of the build
 * compatible extensions - ask of such a class: whether it is a bean, the producers it declares, the scope it
 * declares and whether it has a bean constructor. Every visitor asks here, so that each is answered one way.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class SpecificationBeans {

    /**
     * The scopes of the specification a class may be written with, the singleton pseudo-scope aside.
     */
    private static final Set<String> SCOPES = Set.of(
        Cdi.APPLICATION_SCOPED,
        Cdi.REQUEST_SCOPED,
        Cdi.SESSION_SCOPED,
        Cdi.CONVERSATION_SCOPED,
        Cdi.DEPENDENT
    );

    private SpecificationBeans() {
    }

    /**
     * Whether the class is subject to the rules of the specification: the scope {@code CdiScopeVisitor} resolved for
     * it - which a scope of Micronaut's own, such as {@code Refreshable}, is not - a stereotype, the interceptor
     * annotation, or a producer, an observer or a disposer it declares, each of which makes the class a bean
     * whatever else it declares. Asked after {@code CdiScopeVisitor} has visited the class.
     *
     * @param element The class
     * @return Whether it is a bean of the specification
     */
    public static boolean isBeanOfTheSpecification(ClassElement element) {
        AnnotationMetadata metadata = element.getAnnotationMetadata();
        if (metadata.hasStereotype(CdiScope.class)
            || metadata.hasStereotype(Cdi.NORMAL_SCOPE)
            || metadata.hasStereotype(Cdi.STEREOTYPE)
            || metadata.hasStereotype("jakarta.interceptor.Interceptor")) {
            return true;
        }
        if (!producersOf(element).isEmpty()) {
            return true;
        }
        // an observer method is inherited, where a producer and a disposer are not (section 4.2)
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (Arrays.stream(method.getParameters()).anyMatch(
                parameter -> parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)
                    || (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)
                        && method.getDeclaringType().getName().equals(element.getName())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the class is a bean a lookup of the specification resolves to: a {@link #isBeanOfTheSpecification
     * bean of the specification}, or a class written with the singleton pseudo-scope, which the specification
     * names a scope of its own (section 2.4) and which may be named and is qualified by default like any other
     * bean. The rules a bean of the specification is held to while it compiles are not applied to such a class:
     * Micronaut writes the same annotation on beans that keep its own rules.
     *
     * @param element The class
     * @return Whether it is a bean a lookup resolves to
     */
    public static boolean isBean(ClassElement element) {
        return isBeanOfTheSpecification(element) || element.hasStereotype("jakarta.inject.Singleton");
    }

    /**
     * The producers the class declares, methods first: a producer is not inherited (section 4.2), so one a
     * superclass declares is the superclass's.
     *
     * @param element The class
     * @return The producer methods and fields
     */
    public static List<MemberElement> producersOf(ClassElement element) {
        List<MemberElement> producers = new ArrayList<>();
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            if (method.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                producers.add(method);
            }
        }
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared()).stream()
            .filter(field -> field.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(producers::add);
        return producers;
    }

    /**
     * Whether the class carries one of the scopes of the specification, as it was written or as the mappers have
     * already read it into this module's scope annotation: both are looked for, so that the answer does not depend
     * on which visitor ran first. The singleton pseudo-scope is not among them: a Micronaut class written with it
     * keeps Micronaut's rules.
     *
     * @param element The class
     * @return Whether it declares a scope of the specification
     */
    static boolean hasScopeOfTheSpecification(ClassElement element) {
        if (element.hasStereotype(CdiScope.class)) {
            return true;
        }
        for (String scope : SCOPES) {
            if (element.hasStereotype(scope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the class is declared in the dependent pseudo-scope, as the specification writes it or as the
     * mapper has already read it into this module's scope annotation.
     *
     * @param element The class
     * @return Whether it declares the dependent scope
     */
    static boolean isDependentScoped(ClassElement element) {
        if (element.hasStereotype(Cdi.DEPENDENT)) {
            return true;
        }
        return element.getAnnotationMetadata().stringValue(CdiScope.class)
            .map(Cdi.DEPENDENT::equals)
            .orElse(false);
    }

    /**
     * Whether the class is in a normal scope, which is what makes it reached through a client proxy.
     *
     * @param element The class
     * @return Whether its scope is normal
     */
    static boolean isNormalScoped(ClassElement element) {
        return element.hasStereotype(Cdi.NORMAL_SCOPE)
            || element.getAnnotationMetadata().booleanValue(CdiScope.class, "normal").orElse(false);
    }

    /**
     * Whether the class declares a constructor a bean can be created through, which section 2.2.5 says is one
     * taking no parameters or one annotated {@code Inject}.
     *
     * <p>Micronaut's own choice of a constructor ({@code BeanInjectionUtils.findBeanConstructor}) answers another
     * question - which constructor Micronaut would call: a static creator, the first public constructor, and not a
     * private one unless it is {@code ReflectiveAccess} - so the rule of the specification is read here.</p>
     *
     * @param element The class
     * @return Whether it has a bean constructor
     */
    static boolean hasABeanConstructor(ClassElement element) {
        List<ConstructorElement> constructors = element.getEnclosedElements(ElementQuery.CONSTRUCTORS);
        if (constructors.isEmpty()) {
            // the implicit constructor takes no parameters
            return true;
        }
        for (ConstructorElement constructor : constructors) {
            if (constructor.getParameters().length == 0
                || constructor.hasDeclaredAnnotation("jakarta.inject.Inject")) {
                return true;
            }
        }
        return false;
    }
}
