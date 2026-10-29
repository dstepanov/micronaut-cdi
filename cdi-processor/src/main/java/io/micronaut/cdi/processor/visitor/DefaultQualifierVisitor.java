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

import io.micronaut.cdi.internal.metadata.CdiAny;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

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
 * <p>Only the bean half of the rule is written onto the bean here. The injection point half is the container's:
 * a lookup of the specification resolves by the qualifiers of the bean, {@code Default} among them. A point of a
 * bean of Micronaut's own that names no qualifier where several beans of the specification are candidates is
 * resolved to the one with the default qualifier by the container's resolution customizer
 * ({@code CdiResolutionCustomizer.resolveNonUniqueBean}), which Micronaut asks once no primary bean decided: the
 * bean is not declared Micronaut's primary, which would stand beside an application's own.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class DefaultQualifierVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The qualifiers that do not count as declaring one, per the rule of the specification.
     */
    private static final Set<String> NOT_A_DECLARED_QUALIFIER = Cdi.withMicronautQualifiers(
        "jakarta.inject.Named",
        Cdi.ANY,
        CdiAny.class.getName(),
        Cdi.DEFAULT
        // and what Micronaut writes of its own accord - its primary, which an application writes as well: a
        // qualifier, but not the bean declaring one
    );

    @Override
    public TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return TypeElementQuery.onlyClass();
    }

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
        // a bean written with the singleton pseudo-scope and nothing else is still a bean that an injection point
        // naming no qualifier has to resolve to
        if (!SpecificationBeans.isBean(element)) {
            return;
        }
        qualifyByDefault(element, false);
        // a producer declares its own qualifiers, so the rule is applied to it in its own right
        SpecificationBeans.producersOf(element).forEach(producer -> qualifyByDefault(producer, true));
    }

    private static void qualifyByDefault(Element element, boolean declaredOnly) {
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
    }
}
