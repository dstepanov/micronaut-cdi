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

import io.micronaut.cdi.annotation.CdiRecordedType;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.WildcardElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a type as the compiler sees it - its arguments, wildcards and type variables included - as the record
 * the container composes a {@code java.lang.reflect.Type} from, so that the type is not read back from a
 * class.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RecordedTypeValues {

    private RecordedTypeValues() {
    }

    /**
     * The record of a type.
     *
     * @param type The type
     * @return The record
     */
    public static AnnotationValue<CdiRecordedType> of(ClassElement type) {
        return of(type, Map.of(), new HashSet<>());
    }

    /**
     * The record of a type written in a class the given class inherits it from: a type variable of the
     * declaring class is recorded as what the inheriting class makes of it.
     *
     * @param type     The type, as the declaring class wrote it
     * @param bindings What the inheriting class binds the variables of the declaring class to, by name
     * @return The record
     */
    public static AnnotationValue<CdiRecordedType> of(ClassElement type, Map<String, ClassElement> bindings) {
        return of(type, bindings, new HashSet<>());
    }

    /**
     * The record of a bean type: a type variable and a wildcard among its arguments are recorded with their
     * bounds erased to their classes, which is what typesafe resolution compares a bound as.
     *
     * @param type     The type
     * @param bindings What the variables the type names are bound to, by name
     * @return The record
     */
    public static AnnotationValue<CdiRecordedType> ofBeanType(ClassElement type, Map<String, ClassElement> bindings) {
        return of(type, bindings, new HashSet<>(), true);
    }

    private static AnnotationValue<CdiRecordedType> of(ClassElement type, Map<String, ClassElement> bindings,
                                                       Set<String> variables) {
        return of(type, bindings, variables, false);
    }

    private static AnnotationValue<CdiRecordedType> of(ClassElement type, Map<String, ClassElement> bindings,
                                                       Set<String> variables, boolean erasedBounds) {
        AnnotationValueBuilder<CdiRecordedType> record = AnnotationValue.builder(CdiRecordedType.class);
        if (type instanceof WildcardElement wildcard) {
            record.member("kind", "WILDCARD");
            member(record, CdiRecordedType.BOUNDS, wildcard.getUpperBounds(), bindings, variables, erasedBounds, true);
            member(record, CdiRecordedType.LOWER_BOUNDS, wildcard.getLowerBounds(), bindings, variables, erasedBounds, true);
            return record.build();
        }
        if (type instanceof GenericPlaceholderElement placeholder && !type.isArray()) {
            String name = placeholder.getVariableName();
            ClassElement bound = bindings.get(name);
            if (bound != null && !(bound instanceof GenericPlaceholderElement other
                && other.getVariableName().equals(name))) {
                // what the inheriting class gives the variable: a type, or a variable of its own
                return of(bound, Map.of(), variables, erasedBounds);
            }
            record.member("kind", "VARIABLE").member("name", name);
            if (variables.add(name)) {
                // a variable bounded by itself, as T extends Comparable<T> is, names itself within its bound
                member(record, CdiRecordedType.BOUNDS, placeholder.getBounds(), bindings, variables, erasedBounds, true);
                variables.remove(name);
            }
            return record.build();
        }
        int dimensions = 0;
        ClassElement component = type;
        while (component.isArray()) {
            dimensions++;
            component = component.fromArray();
        }
        if (dimensions > 0) {
            record.member("dimensions", dimensions);
        }
        if (component.isPrimitive()) {
            return record.member("kind", "PRIMITIVE").member("name", component.getName()).build();
        }
        record.member("value", new AnnotationClassValue<>(component.getName()));
        if (!component.isRawType() && !component.getTypeArguments().isEmpty()) {
            member(record, CdiRecordedType.ARGUMENTS, component.getTypeArguments().values(), bindings, variables, erasedBounds, false);
        }
        return record.build();
    }

    private static void member(AnnotationValueBuilder<CdiRecordedType> record, String name,
                               Collection<? extends ClassElement> types, Map<String, ClassElement> bindings,
                               Set<String> variables, boolean erasedBounds, boolean bounds) {
        List<AnnotationValue<?>> records = new ArrayList<>(types.size());
        for (ClassElement type : types) {
            if (bounds && erasedBounds && !(type instanceof GenericPlaceholderElement)
                && !(type instanceof WildcardElement) && !type.isPrimitive()) {
                // the class of the bound alone
                AnnotationValueBuilder<CdiRecordedType> erased = AnnotationValue.builder(CdiRecordedType.class);
                ClassElement component = type;
                int dimensions = 0;
                while (component.isArray()) {
                    dimensions++;
                    component = component.fromArray();
                }
                erased.member("value", new AnnotationClassValue<>(component.getName()));
                if (dimensions > 0) {
                    erased.member("dimensions", dimensions);
                }
                records.add(erased.build());
                continue;
            }
            records.add(of(type, bindings, variables, erasedBounds));
        }
        if (!records.isEmpty()) {
            record.member(name, records.toArray(new AnnotationValue<?>[0]));
        }
    }
}
