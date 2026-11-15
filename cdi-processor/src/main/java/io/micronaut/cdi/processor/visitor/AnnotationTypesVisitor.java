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

import io.micronaut.cdi.internal.metadata.CdiAnnotationTypes;
import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.mapper.NonbindingMembers;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records on each class what it uses of qualifier and interceptor binding types where it is injected or invoked
 * ({@link CdiAnnotationTypes}): the container asks of an annotation type whether it is a qualifier or a binding, its
 * members and the ones that do not bind, and the class level metadata of the beans answers that without a definition
 * being loaded.
 *
 * <p>Every class the compilation visits is recorded, its constructors, fields and methods - inherited ones included -
 * and their parameters, each from the class itself: nothing is kept from one class to the next. What is written is
 * annotation metadata on the class, part of the definition Micronaut writes for it; no class is generated.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class AnnotationTypesVisitor implements TypeElementVisitor<Object, Object> {

    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";

    @Override
    public TypeElementQuery query() {
        return TypeElementQuery.onlyClass();
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * Runs after every other visitor of this module: the qualifiers and bindings recorded are the ones the others
     * left on the members.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 500;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element instanceof AnnotationElement) {
            return;
        }
        Types types = new Types();
        for (ConstructorElement constructor : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            for (ParameterElement parameter : constructor.getParameters()) {
                types.read(parameter.getAnnotationMetadata());
            }
        }
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            types.read(field.getAnnotationMetadata());
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (method.getDeclaringType().getName().equals(Object.class.getName())) {
                continue;
            }
            types.read(method.getAnnotationMetadata());
            for (ParameterElement parameter : method.getParameters()) {
                types.read(parameter.getAnnotationMetadata());
            }
        }
        element.annotate(types.record(context));
    }

    /**
     * The qualifier and binding types read off the members of one class.
     */
    private static final class Types {

        private final Map<String, Set<String>> qualifiers = new LinkedHashMap<>();
        private final Map<String, Set<String>> bindings = new LinkedHashMap<>();
        private final Set<String> runtime = new LinkedHashSet<>();

        void read(AnnotationMetadata metadata) {
            if (metadata.isEmpty()) {
                return;
            }
            // an annotation that only carries a qualifier or a binding - a stereotype of the specification - is not
            // one, as the container reads the metadata
            List<String> stereotypes = metadata.getAnnotationNamesByStereotype(Cdi.STEREOTYPE);
            for (AnnotationValue<Annotation> qualifier : metadata.getAnnotationValuesByStereotype(AnnotationUtil.QUALIFIER)) {
                if (qualifier != null && !stereotypes.contains(qualifier.getAnnotationName())) {
                    occurrence(qualifiers, qualifier);
                }
            }
            for (String name : metadata.getAnnotationNamesByStereotype(BINDING)) {
                if (!stereotypes.contains(name)) {
                    for (AnnotationValue<Annotation> binding : metadata.getAnnotationValuesByName(name)) {
                        occurrence(bindings, binding);
                    }
                }
            }
        }

        private void occurrence(Map<String, Set<String>> types, AnnotationValue<?> occurrence) {
            Set<String> nonbinding = types.computeIfAbsent(occurrence.getAnnotationName(),
                name -> new LinkedHashSet<>());
            // the members Micronaut lists as not binding on each occurrence, a build compatible extension's marks
            // included
            nonbinding.addAll(List.of(occurrence.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)));
            if (occurrence.getRetentionPolicy() == RetentionPolicy.RUNTIME) {
                runtime.add(occurrence.getAnnotationName());
            }
        }

        AnnotationValue<CdiAnnotationTypes> record(VisitorContext context) {
            Map<String, Set<String>> all = new LinkedHashMap<>(qualifiers);
            bindings.forEach((name, nonbinding) -> all.merge(name, nonbinding, (one, other) -> {
                Set<String> both = new LinkedHashSet<>(one);
                both.addAll(other);
                return both;
            }));
            List<AnnotationValue<CdiExtensionQualifiers.Members>> members = new ArrayList<>(all.size());
            all.forEach((name, occurring) -> {
                ClassElement declaration = NonbindingMembers.declarationOf(name, context);
                if (declaration == null) {
                    // the interface is not on the compilation's classpath: the name alone says nothing of its
                    // members, and no record of them is written
                    return;
                }
                Set<String> nonbinding = new LinkedHashSet<>(NonbindingMembers.nonbindingMembersOf(declaration));
                nonbinding.addAll(occurring);
                nonbinding.remove(AnnotationUtil.NON_BINDING_ATTRIBUTE);
                nonbinding.remove(AnnotationUtil.STEREOTYPES_MEMBER);
                members.add(AnnotationValue.builder(CdiExtensionQualifiers.Members.class)
                    .member(CdiExtensionQualifiers.Members.NAME, name)
                    .member(CdiExtensionQualifiers.MEMBERS,
                        NonbindingMembers.memberElementsOf(declaration).keySet().toArray(new String[0]))
                    .member(CdiExtensionQualifiers.Members.NONBINDING, nonbinding.toArray(new String[0]))
                    .build());
            });
            return AnnotationValue.builder(CdiAnnotationTypes.class)
                .member(CdiAnnotationTypes.QUALIFIERS, qualifiers.keySet().toArray(new String[0]))
                .member(CdiAnnotationTypes.BINDINGS, bindings.keySet().toArray(new String[0]))
                .member(CdiAnnotationTypes.RUNTIME, runtime.toArray(new String[0]))
                .member(CdiExtensionQualifiers.MEMBERS, members.toArray(new AnnotationValue[0]))
                .build();
        }
    }
}
