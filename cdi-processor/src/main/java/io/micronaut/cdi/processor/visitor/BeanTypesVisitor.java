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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
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
 * generic signatures of the classes to work it out again. So it is written here, on the class and on the
 * producer, and the container reads the record.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BeanTypesVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public io.micronaut.inject.visitor.TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return io.micronaut.inject.visitor.TypeElementQuery.onlyClass();
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
        if (!element.isInterface() && !element.isAbstract()) {
            record(element, element);
        }
        for (io.micronaut.inject.ast.MemberElement producer : SpecificationBeans.producersOf(element)) {
            if (producer instanceof MethodElement method) {
                record(method.getGenericReturnType(), method);
            } else if (producer instanceof FieldElement field) {
                record(field.getGenericType(), field);
            }
        }
    }

    private static void record(ClassElement type, Element on) {
        List<AnnotationValue<CdiRecordedType>> closure = new ArrayList<>();
        try {
            collect(type, java.util.Map.of(), closure, true, false);
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own, and is left to the
            // compiler to report
            return;
        }
        on.annotate(CdiBeanTypes.class, builder ->
            builder.member("value", closure.toArray(new AnnotationValue<?>[0])));
    }

    /**
     * The records of the type closure of a type, the type first.
     *
     * @param type         The type
     * @param erasedBounds Whether the bounds of a variable and of a wildcard are recorded as classes alone
     * @return The records
     */
    public static List<AnnotationValue<CdiRecordedType>> closureOf(ClassElement type, boolean erasedBounds) {
        List<AnnotationValue<CdiRecordedType>> closure = new ArrayList<>();
        collect(type, java.util.Map.of(), closure, erasedBounds, false);
        return closure;
    }

    /**
     * Collects the type closure of a type, as {@link #walkClosure} walks it.
     */
    private static void collect(ClassElement type, java.util.Map<String, ClassElement> bindings,
                                List<AnnotationValue<CdiRecordedType>> closure, boolean erasedBounds,
                                boolean erased) {
        walk(type, bindings, erased, (member, memberBindings, memberErased) -> {
            if (memberErased) {
                closure.add(RecordedTypeValues.ofErasure(member));
            } else {
                closure.add(erasedBounds ? RecordedTypeValues.ofBeanType(member, memberBindings)
                    : RecordedTypeValues.of(member, memberBindings));
            }
        });
    }

    /**
     * Walks the type closure of a type: the type, the interfaces it implements and the class it extends, and
     * theirs in turn, each with the variables it names bound to what the type below it gives them. An array
     * and a primitive have no closure beyond themselves, and {@code Object}, which every closure ends in, is
     * left for the reader to add once.
     *
     * <p>The supertypes of a raw type are erased, as the language has them (JLS 4.8): a raw {@code Box} that
     * implements {@code View<T>} is a {@code View}, not a {@code View<T>} that any lookup of a {@code View} would
     * match. What records the closure for the container and what describes it to the registration phase both
     * walk it here, so that the two agree.</p>
     *
     * @param type   The type
     * @param member What is done with each type of the closure
     */
    public static void walkClosure(ClassElement type, ClosureMember member) {
        walk(type, java.util.Map.of(), false, member);
    }

    private static void walk(ClassElement type, java.util.Map<String, ClassElement> bindings, boolean erased,
                             ClosureMember member) {
        if ("java.lang.Object".equals(type.getName()) && !type.isArray()) {
            return;
        }
        member.accept(type, bindings, erased);
        if (type.isArray() || type.isPrimitive()) {
            return;
        }
        boolean raw = erased || type.isRawType() && !type.getTypeArguments().isEmpty();
        // what this type gives its own variables: an argument written as a variable of the type below is what
        // the type below was given
        java.util.Map<String, ClassElement> own = new java.util.LinkedHashMap<>();
        type.getTypeArguments().forEach((name, argument) -> {
            ClassElement bound = argument instanceof io.micronaut.inject.ast.GenericPlaceholderElement placeholder
                ? bindings.get(placeholder.getVariableName()) : null;
            own.put(name, bound != null ? bound : argument);
        });
        for (ClassElement anInterface : type.getInterfaces()) {
            walk(anInterface, own, raw, member);
        }
        type.getSuperType().ifPresent(superType -> walk(superType, own, raw, member));
    }

    /**
     * What is done with one type of a type closure.
     */
    @FunctionalInterface
    public interface ClosureMember {

        /**
         * Takes one type of the closure.
         *
         * @param type     The type, as the type below it names it
         * @param bindings What the type below it gives the variables the type names
         * @param erased   Whether the type is erased, being a supertype of a raw type
         */
        void accept(ClassElement type, java.util.Map<String, ClassElement> bindings, boolean erased);
    }
}
