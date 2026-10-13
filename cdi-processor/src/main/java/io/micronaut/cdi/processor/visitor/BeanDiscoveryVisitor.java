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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

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

    @Override
    public io.micronaut.inject.visitor.TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return io.micronaut.inject.visitor.TypeElementQuery.onlyClass();
    }

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
        if (SpecificationBeans.isVetoed(element)) {
            notABean(element);
            return;
        }
        for (String typedName : element.getAnnotationMetadata()
            .stringValues(Cdi.TYPED)) {
            // section 2.2.2: every type named by @Typed must be a type the class actually has
            // the metadata holds binary names, in which a nested type is Outer$Inner; assignability is asked
            // with the source form
            if (!"java.lang.Object".equals(typedName) && !element.isAssignable(typedName)
                && !element.isAssignable(typedName.replace('$', '.'))) {
                context.fail("The class " + element.getName() + " restricts its bean types to "
                    + typedName + ", which is not one of its types (section 2.2.2)", element);
                return;
            }
        }
        if (element.hasDeclaredAnnotation("jakarta.decorator.Decorator")) {
            // decorators belong to CDI Full; a class written as one is not a bean of CDI Lite, and treating it
            // as a plain bean would be worse than leaving it out — its delegate injection point would be an
            // ambiguity between the decorator and what it decorates
            notABean(element);
            return;
        }
        if (SpecificationBeans.hasScopeOfTheSpecification(element)
            && !element.getDeclaredGenericPlaceholders().isEmpty()
            && !SpecificationBeans.isDependentScoped(element)) {
            // section 2.2.1 allows a generic class to be a managed bean only in the dependent scope: there is
            // no one bean a scoped context could hold for a class that is a different type each place it is
            // asked for. The container detects it as a definition error, and this container detects it here
            context.fail("A generic class may only be a dependent-scoped bean: " + element.getName()
                + " declares another scope", element);
            return;
        }
        if (element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor")
            && SpecificationBeans.hasScopeOfTheSpecification(element)
            && !SpecificationBeans.isDependentScoped(element)) {
            // section 2.7 has an interceptor be a dependent object of what it intercepts: one shared instance
            // in a wider scope would interleave the invocations of every intercepted bean
            context.fail("An interceptor is dependent scoped: " + element.getName()
                + " declares another scope", element);
            return;
        }
        if (SpecificationBeans.hasScopeOfTheSpecification(element)
            && !SpecificationBeans.hasABeanConstructor(element)) {
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
            Alternatives.leaveUnselected(element, element.getName(), false);
            return;
        }
        // a selected alternative is preferred over what it is an alternative to, and preferred over another
        // alternative of a lower priority: it is primary, so an unqualified injection point Micronaut resolves
        // prefers it, and it is ordered by its priority, so that of several candidates the one the highest
        // priority wins — which is the resolution rule of section 2.4.2
        Alternatives.select(element, priority, true);
    }

    /**
     * Takes a class out of the set of beans, by giving its bean definition a condition that never holds.
     */
    private static void notABean(ClassElement element) {
        element.annotate(Requires.class, builder -> builder
            .member("condition", new AnnotationClassValue<>("io.micronaut.cdi.internal.runtime.NotABean")));
    }
}
