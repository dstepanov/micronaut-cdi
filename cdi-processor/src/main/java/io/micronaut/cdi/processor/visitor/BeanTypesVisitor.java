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

import io.micronaut.cdi.internal.metadata.CdiBeanTypes;
import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the type closure of a bean as it is compiled (section 2.2.1): of a class, and of what each producer
 * it declares produces.
 *
 * <p>The bean types of a bean are its class, or the type its producer returns, and everything above that with
 * the type arguments the hierarchy gives it. The compiler knows all of it; the runtime would have to read the
 * generic signatures of the classes to work it out again. So the hierarchy of the bean class, and of the class a
 * producer produces, is recorded as Micronaut's type hierarchy record of the class ({@link TypeHierarchyRecords}),
 * which the container reads the closure from for the class over its own variables, or for the type the producer
 * declared as its definition holds it. Where the record may not say the closure in full - Micronaut refused the
 * hierarchy, or a supertype is a class that is not public and of another package, which the record leaves out - the
 * closure is written on the class or on the producer as well ({@link CdiBeanTypes}), and the container reads it where
 * the record it finds names a supertype it left out.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BeanTypesVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return TypeElementQuery.onlyClass();
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        // after the producers have been read: a produced type is recorded on the member that produces it
        return LOWEST_PRECEDENCE - 350;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element instanceof AnnotationElement || element.isEnum()) {
            return;
        }
        if (!element.isInterface() && !element.isAbstract() && isBeanClass(element)
            && !TypeHierarchyRecords.record(element, element, context)) {
            record(element, element);
        }
        for (MemberElement producer : SpecificationBeans.producersOf(element)) {
            ClassElement produced = producer instanceof MethodElement method ? method.getGenericReturnType()
                : producer instanceof FieldElement field ? field.getGenericType() : null;
            if (produced != null && !answeredWithoutRecord(produced)
                && !TypeHierarchyRecords.record(produced, producer, context)) {
                record(produced, producer);
            }
        }
    }

    /**
     * Whether the closure of a produced type is said without a record of its class: an array and a primitive have
     * none beyond themselves (section 2.2.1), and a type variable none of its own.
     */
    private static boolean answeredWithoutRecord(ClassElement produced) {
        return produced.isArray() || produced.isPrimitive()
            || produced instanceof GenericPlaceholderElement
            || produced instanceof WildcardElement
            || "java.lang.Object".equals(produced.getName());
    }

    /**
     * Whether the class is one a definition is written for, which its type closure is recorded on: a bean of the
     * specification, or of Micronaut's own. A class that is neither has no definition to carry the record, and the
     * type hierarchy visitor records what of it is needed ({@link TypeHierarchyVisitor}).
     */
    private static boolean isBeanClass(ClassElement element) {
        return element.hasStereotype(CdiScope.class)
            || element.hasStereotype(AnnotationUtil.SCOPE)
            || element.hasStereotype("io.micronaut.context.annotation.Bean")
            || element.hasStereotype("io.micronaut.context.annotation.Context")
            || SpecificationBeans.isBean(element);
    }

    private static void record(ClassElement type, Element on) {
        List<AnnotationValue<CdiRecordedType>> closure = new ArrayList<>();
        try {
            // a type variable and a wildcard among the arguments are recorded with their bounds erased to their
            // classes, which is what typesafe resolution compares a bound as; a raw type is its class alone
            for (ClassElement member : closureOf(type)) {
                closure.add(RecordedTypeValues.ofBeanType(member));
            }
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own, and is left to the
            // compiler to report
            return;
        }
        on.annotate(CdiBeanTypes.class, builder ->
            builder.member(AnnotationMetadata.VALUE_MEMBER, closure.toArray(new AnnotationValue<?>[0])));
    }

    /**
     * The type closure of a type: the type and its supertypes as Micronaut lists them
     * ({@link ClassElement#getAllSuperTypes()}), each with the type arguments the type below it gives it, and each
     * once however many paths of the hierarchy reach it. An array and a primitive have no closure beyond themselves
     * (section 2.2.1), and {@code Object}, which every closure ends in, is left for the reader to add once.
     *
     * <p>The supertypes of a raw type are raw, as the language has them (JLS 4.8): a raw {@code Box} that
     * implements {@code View<T>} is a {@code View}, not a {@code View<T>} that any lookup of a {@code View} would
     * match. What records the closure for the container and what describes it to the registration phase both
     * read it here, so that the two agree.</p>
     *
     * @param type The type
     * @return The types of the closure, the type first and without {@code Object}
     */
    public static List<ClassElement> closureOf(ClassElement type) {
        if (type.isArray() || type.isPrimitive()) {
            return List.of(type);
        }
        List<ClassElement> closure = new ArrayList<>(type.getAllSuperTypes());
        closure.removeIf(member -> "java.lang.Object".equals(member.getName()) && !member.isArray());
        return closure;
    }
}
