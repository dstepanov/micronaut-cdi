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
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Gives every bean of the specification that declares no qualifier the default qualifier, which is the one the
 * specification says such a bean has.
 *
 * <p>The rule is that a bean which declares no qualifier other than {@code Named} or {@code Any} has exactly one
 * more qualifier, {@code Default}, and that an injection point which declares no qualifier is looking for it. The
 * two halves of that rule are what tell a bean written for no particular use apart from one written for a
 * particular one, and without it the two would be indistinguishable and every such pair ambiguous.</p>
 *
 * <p>Only the bean half of the rule is written onto the bean here. The injection point half is left to Micronaut,
 * which resolves an injection point that names no qualifier to the primary bean of the type when there is more
 * than one candidate; declaring the default bean primary is what makes that resolution pick the same bean the
 * specification's rule would. It is also what keeps a bean of this specification injectable into one that is not:
 * an injection point of a plain Micronaut bean is not rewritten, so it goes on resolving the way it did.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class DefaultQualifierVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The qualifiers that do not count as declaring one, per the rule of the specification.
     */
    private static final Set<String> NOT_A_DECLARED_QUALIFIER = Set.of(
        "jakarta.inject.Named",
        Cdi.ANY,
        "io.micronaut.cdi.annotation.CdiAny",
        Cdi.DEFAULT,
        // what this module's own visitors write on a selected alternative: Micronaut's primary is a
        // qualifier, but it is not the bean declaring one
        "io.micronaut.context.annotation.Primary",
        "io.micronaut.context.annotation.Order"
    );

    /**
     * The scopes of the specification. A class annotated with one of them, directly or through a stereotype, is a
     * bean of the specification and so is subject to its rule about qualifiers.
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
     * Runs last of the visitors of this module: what qualifies a bean depends on everything the others decided.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 400;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!isBeanOfTheSpecification(element)) {
            return;
        }
        List<MemberElement> producers = new ArrayList<>();
        element.getEnclosedElements(ElementQuery.ALL_METHODS)
            .stream()
            .filter(m -> m.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(producers::add);
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)
            .stream()
            .filter(f -> f.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(producers::add);
        qualifyByDefault(element, true, false);
        // a producer declares its own qualifiers, so the rule is applied to it in its own right
        producers.forEach(producer -> qualifyByDefault(producer, true, true));
    }

    /**
     * Whether the class is a bean of this specification rather than a plain Micronaut one, which is what makes it
     * subject to the rule. A class that produces beans is one even when it declares no scope itself.
     */
    private static boolean isBeanOfTheSpecification(ClassElement element) {
        AnnotationMetadata metadata = element.getAnnotationMetadata();
        if (metadata.hasStereotype(CdiScope.class)) {
            return true;
        }
        for (String scope : SCOPES) {
            if (metadata.hasStereotype(scope)) {
                return true;
            }
        }
        return element.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
            .anyMatch(m -> m.hasDeclaredAnnotation(Cdi.PRODUCES))
            || element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .anyMatch(f -> f.hasDeclaredAnnotation(Cdi.PRODUCES));
    }

    private static void qualifyByDefault(Element element, boolean primary, boolean declaredOnly) {
        for (String qualifier : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (NOT_A_DECLARED_QUALIFIER.contains(qualifier)) {
                continue;
            }
            // a qualifier a class inherits from a superclass, because the qualifier is annotated Inherited, is a
            // qualifier of the class as much as one it wrote itself, and section 2.1.3 counts it. A member is
            // read more narrowly: its annotation metadata carries what its class declares as well, and the class
            // has usually been given the default qualifier by this point, so what it carries is looked past
            if (!declaredOnly || element.hasDeclaredAnnotation(qualifier)) {
                return;
            }
        }
        element.annotate(Cdi.DEFAULT);
        if (primary) {
            // and so an injection point that names no qualifier resolves to it rather than being ambiguous
            element.annotate(Primary.class);
        }
    }
}
