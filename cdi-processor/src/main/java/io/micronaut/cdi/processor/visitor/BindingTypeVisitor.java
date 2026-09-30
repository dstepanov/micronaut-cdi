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

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records what the container needs to know of a qualifier or an interceptor binding type without reading the
 * annotation class: the members it declares, which of them take no part in resolution, whether it may be
 * repeated, and whether it is retained at runtime.
 *
 * <p>Compiled metadata records an annotation where it is used, with the members that use wrote. What a
 * container is asked about an annotation <em>type</em> - is this a qualifier, is it a marker, may it be given
 * twice - is answered by the annotation class, which the runtime does not read. So the answers are written
 * while the application compiles, as a resource named after the annotation, for every qualifier and binding
 * type the compilation declares or uses.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BindingTypeVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * Where the records are written, under {@code META-INF}.
     */
    public static final String LOCATION = "micronaut-cdi/bindings/";

    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";
    private static final String MICRONAUT_NONBINDING = "io.micronaut.context.annotation.NonBinding";

    private final Set<String> written = new HashSet<>();

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element instanceof AnnotationElement
            && (element.hasDeclaredAnnotation(Cdi.QUALIFIER) || element.hasDeclaredAnnotation(BINDING))) {
            record(element, element, context);
        }
        recordUsed(element.getAnnotationMetadata(), element, context);
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            recordUsed(field.getAnnotationMetadata(), element, context);
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            recordUsed(method.getAnnotationMetadata(), element, context);
            for (ParameterElement parameter : method.getParameters()) {
                recordUsed(parameter.getAnnotationMetadata(), element, context);
            }
        }
        for (MethodElement constructor : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            for (ParameterElement parameter : constructor.getParameters()) {
                recordUsed(parameter.getAnnotationMetadata(), element, context);
            }
        }
    }

    private void recordUsed(AnnotationMetadata metadata, ClassElement origin, VisitorContext context) {
        if (metadata.isEmpty()) {
            return;
        }
        Set<String> names = new HashSet<>(metadata.getAnnotationNamesByStereotype(Cdi.QUALIFIER));
        names.addAll(metadata.getAnnotationNamesByStereotype(AnnotationUtil.QUALIFIER));
        names.addAll(metadata.getAnnotationNamesByStereotype(BINDING));
        for (String name : names) {
            if (!written.contains(name) && !isMicronautOwn(name)) {
                // a nested annotation is recorded by its binary name, and asked of the compiler by its canonical one
                context.getClassElement(name)
                    .or(() -> context.getClassElement(name.replace('$', '.')))
                    .ifPresent(type -> record(type, origin, context));
            }
        }
    }

    private static boolean isMicronautOwn(String name) {
        return name.startsWith("io.micronaut.context.annotation.") || name.startsWith("io.micronaut.core.annotation.")
            || name.startsWith("io.micronaut.cdi.annotation.") || name.startsWith("io.micronaut.aop.");
    }

    private void record(ClassElement type, ClassElement origin, VisitorContext context) {
        if (!written.add(type.getName())) {
            return;
        }
        boolean qualifier = type.hasDeclaredAnnotation(Cdi.QUALIFIER);
        boolean binding = type.hasDeclaredAnnotation(BINDING);
        if (!qualifier && !binding) {
            // an annotation that only carries a qualifier or a binding is not one itself
            return;
        }
        List<String> members = new ArrayList<>();
        List<String> nonbinding = new ArrayList<>();
        for (MethodElement member : type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            members.add(member.getName());
            if (member.hasAnnotation(Cdi.NONBINDING) || member.hasAnnotation(MICRONAUT_NONBINDING)) {
                nonbinding.add(member.getName());
            }
        }
        boolean repeatable = type instanceof AnnotationElement annotation
            && annotation.getRepeatableContainer().isPresent();
        boolean runtime = !(type instanceof AnnotationElement annotation)
            || annotation.getRetentionPolicy() == RetentionPolicy.RUNTIME;
        String record = "qualifier=" + qualifier + "\n"
            + "binding=" + binding + "\n"
            + "members=" + String.join(",", members) + "\n"
            + "nonbinding=" + String.join(",", nonbinding) + "\n"
            + "repeatable=" + repeatable + "\n"
            + "runtime=" + runtime + "\n";
        try {
            context.visitMetaInfFile(LOCATION + type.getName(), origin).ifPresent(file -> {
                try {
                    file.write(writer -> writer.write(record));
                } catch (java.io.IOException e) {
                    context.warn("The record of the binding type " + type.getName() + " could not be written: "
                        + e.getMessage(), origin);
                }
            });
        } catch (RuntimeException e) {
            context.warn("The record of the binding type " + type.getName() + " could not be written: " + e, origin);
        }
    }
}
