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
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.cdi.annotation.NotABean;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.PackageElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.List;
import java.util.Set;

/**
 * Decides which of the classes that look like beans are beans, which is the part of discovery the specification
 * leaves to the annotations rather than to the packaging.
 *
 * <p>Two annotations take a class out of the set of beans. {@code Vetoed} says outright that the class is not one,
 * and section 2.11.5 has it apply to a package as well as to a class. {@code Alternative} says that the class is a
 * bean only where it has been selected, and section 2.1.7 has it selected by a priority: an alternative that
 * declares none is not enabled, and is no more a bean than a vetoed class is.</p>
 *
 * <p>A class is taken out of the set by taking the annotations that make it a bean off it, so that Micronaut
 * generates no bean definition for it at all. That is the same thing the specification means by a class not being
 * a bean: it is still a class, and can still be instantiated by whoever wants to.</p>
 *
 * <p>An alternative that <em>is</em> selected replaces the beans it is an alternative to, which Micronaut says
 * with {@code Replaces}: a bean that replaces a type replaces every bean assignable to that type, which is the
 * rule the specification gives in terms of bean types. The type it replaces is the one it was written as an
 * alternative to — the class it extends, or the interface it implements.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BeanDiscoveryVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The scopes of the specification, one of which a bean of it carries.
     */
    private static final Set<String> SCOPES = Set.of(
        Cdi.APPLICATION_SCOPED,
        Cdi.REQUEST_SCOPED,
        Cdi.SESSION_SCOPED,
        Cdi.CONVERSATION_SCOPED,
        Cdi.DEPENDENT
    );

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * Runs after the scope of a class has been read, and before the members that depend on it are read.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 200;
    }

    // every class is visited rather than only the ones carrying an annotation this looks for. A scope of the
    // specification is annotated Inherited, so a class can be a bean of this specification without declaring
    // anything at all: what makes it one was written on a class it extends

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (isVetoed(element)) {
            notABean(element);
            return;
        }
        if (element.hasDeclaredAnnotation("jakarta.decorator.Decorator")) {
            // decorators belong to CDI Full; a class written as one is not a bean of CDI Lite, and treating it
            // as a plain bean would be worse than leaving it out — its delegate injection point would be an
            // ambiguity between the decorator and what it decorates
            notABean(element);
            return;
        }
        if (isBeanOfTheSpecification(element) && !element.getDeclaredGenericPlaceholders().isEmpty()
            && !isDependentScoped(element)) {
            // section 2.2.1 allows a generic class to be a managed bean only in the dependent scope: there is
            // no one bean a scoped context could hold for a class that is a different type each place it is
            // asked for. The container detects it as a definition error, and this container detects it here
            context.fail("A generic class may only be a dependent-scoped bean: " + element.getName()
                + " declares another scope", element);
            return;
        }
        if (element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor")
            && isBeanOfTheSpecification(element) && !isDependentScoped(element)) {
            // section 2.7 has an interceptor be a dependent object of what it intercepts: one shared instance
            // in a wider scope would interleave the invocations of every intercepted bean
            context.fail("An interceptor is dependent scoped: " + element.getName()
                + " declares another scope", element);
            return;
        }
        if (isBeanOfTheSpecification(element) && !hasABeanConstructor(element)) {
            // section 2.2.5 has a managed bean declare either a constructor taking no parameters or one
            // annotated Inject, and a class that declares neither is not a managed bean however it is
            // annotated. It comes up more often than it sounds: a scope of the specification is annotated
            // Inherited, so a subclass of a bean carries the scope whether or not it is a bean itself
            notABean(element);
            return;
        }
        // a stereotype may declare that the beans that carry it are alternatives, so the annotation is looked
        // for through the stereotypes as well as on the class itself
        if (!element.hasStereotype(Cdi.ALTERNATIVE)) {
            return;
        }
        Integer priority = Cdi.priorityOf(element.getAnnotationMetadata());
        if (priority == null) {
            // an alternative that is not selected by a priority is not enabled here — but the SE bootstrap may
            // still select it as the container is built, so instead of being no bean at all it is a bean on
            // the condition that something selected it, with the names that would beside it
            java.util.List<String> stereotypes = element.getAnnotationMetadata()
                .getAnnotationNamesByStereotype(Cdi.ALTERNATIVE).stream()
                .filter(name -> !name.equals(Cdi.ALTERNATIVE))
                .toList();
            element.annotate(Requires.class, builder -> builder
                .member("condition", new AnnotationClassValue<>(
                    "io.micronaut.cdi.annotation.UnselectedAlternative")));
            String className = element.getName();
            element.annotate("io.micronaut.cdi.annotation.CdiSelectableAlternative", builder -> {
                builder.value(className);
                if (!stereotypes.isEmpty()) {
                    builder.member("stereotypes", stereotypes.toArray(new String[0]));
                }
            });
            return;
        }
        // a selected alternative is preferred over what it is an alternative to, and preferred over another
        // alternative of a lower priority. Both are said the Micronaut way: it is primary, so an unqualified
        // injection point prefers it, and it is ordered by its priority, so that of several candidates the one
        // the highest priority wins — which is the resolution rule of section 2.4.2
        element.annotate(Primary.class);
        // Micronaut reads a jakarta priority as an order of the same value, and a Micronaut order prefers the
        // lowest; the specification prefers the highest priority, so the order is written negated — and written
        // over what was read, not beside it
        int order = -priority;
        element.removeAnnotation("io.micronaut.core.annotation.Order");
        element.annotate("io.micronaut.core.annotation.Order", builder -> builder.value(order));
    }

    /**
     * Whether the class is in the dependent scope, written either as the specification writes it or as the
     * mapper has already read it into this module's scope annotation.
     */
    private static boolean isDependentScoped(ClassElement element) {
        if (element.hasStereotype(Cdi.DEPENDENT)) {
            return true;
        }
        return element.getAnnotationMetadata().stringValue(CdiScope.class)
            .map(Cdi.DEPENDENT::equals)
            .orElse(false);
    }

    /**
     * Whether the class is a bean of this specification, which is to say that it carries one of its scopes
     * either as it was written or as it has already been read into a Micronaut one. Both are looked for, so
     * that this does not depend on which visitor ran first.
     */
    private static boolean isBeanOfTheSpecification(ClassElement element) {
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
     * Whether the class declares a constructor a bean can be created through, which section 2.2.5 says is one
     * taking no parameters or one annotated {@code Inject}.
     */
    private static boolean hasABeanConstructor(ClassElement element) {
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

    /**
     * Whether the class is vetoed, either by itself or by the package it is in.
     */
    private static boolean isVetoed(ClassElement element) {
        if (element.hasDeclaredAnnotation(Cdi.VETOED)) {
            return true;
        }
        PackageElement declaringPackage = element.getPackage();
        return declaringPackage.hasDeclaredAnnotation(Cdi.VETOED);
    }

    /**
     * Takes a class out of the set of beans, by giving its bean definition a condition that never holds.
     */
    private static void notABean(ClassElement element) {
        element.annotate(Requires.class, builder -> builder
            .member("condition", new AnnotationClassValue<>(NotABean.class.getName())));
    }
}
