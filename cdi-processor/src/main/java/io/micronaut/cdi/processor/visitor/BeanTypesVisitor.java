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

import io.micronaut.cdi.annotation.CdiBeanTypes;
import io.micronaut.cdi.annotation.CdiRecordedType;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
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
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (method.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                record(method.getGenericReturnType(), method);
            }
        }
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            if (field.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                record(field.getGenericType(), field);
            }
        }
    }

    private static void record(ClassElement type, Element on) {
        List<AnnotationValue<CdiRecordedType>> closure = new ArrayList<>();
        try {
            collect(type, java.util.Map.of(), closure);
        } catch (RuntimeException e) {
            // a hierarchy the compiler cannot resolve is a broken compilation of its own, and is left to the
            // compiler to report
            return;
        }
        on.annotate(CdiBeanTypes.class, builder ->
            builder.member("value", closure.toArray(new AnnotationValue<?>[0])));
    }

    /**
     * Collects the type closure of a type: the type, the interfaces it implements and the class it extends, and
     * theirs in turn, each with the variables it names bound to what the type below it gives them. An array
     * and a primitive have no closure beyond themselves, and {@code Object}, which every closure ends in, is
     * left for the reader to add once.
     */
    private static void collect(ClassElement type, java.util.Map<String, ClassElement> bindings,
                                List<AnnotationValue<CdiRecordedType>> closure) {
        if ("java.lang.Object".equals(type.getName()) && !type.isArray()) {
            return;
        }
        closure.add(RecordedTypeValues.ofBeanType(type, bindings));
        if (type.isArray() || type.isPrimitive()) {
            return;
        }
        // what this type gives its own variables: an argument written as a variable of the type below is what
        // the type below was given
        java.util.Map<String, ClassElement> own = new java.util.LinkedHashMap<>();
        type.getTypeArguments().forEach((name, argument) -> {
            ClassElement bound = argument instanceof io.micronaut.inject.ast.GenericPlaceholderElement placeholder
                ? bindings.get(placeholder.getVariableName()) : null;
            own.put(name, bound != null ? bound : argument);
        });
        for (ClassElement anInterface : type.getInterfaces()) {
            collect(anInterface, own, closure);
        }
        type.getSuperType().ifPresent(superType -> collect(superType, own, closure));
    }
}
