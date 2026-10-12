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
package io.micronaut.cdi.processor.mapper;

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import io.micronaut.cdi.processor.extension.DiscoveredClasses;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Records on every occurrence of a qualifier or an interceptor binding which of its members take no part in
 * resolution, under {@code $nonBinding}: the one record of them the container compares by.
 *
 * <p>Micronaut lists a member it reads as {@code NonBinding} only on an occurrence that writes the member, and reads
 * whether it is one from the member as the source declares it. Two things are left out by that: an occurrence that
 * leaves a non-binding member at its default says nothing, so a lookup that writes the member down cannot tell that
 * it does not bind; and a member a build compatible extension marked {@code Nonbinding} while the compilation was
 * discovering its classes is not marked in the source. This lists every member the annotation interface marks, and
 * every member the discovery phase marked, on every occurrence, merged with what Micronaut wrote. The qualifier
 * stereotype of the occurrence is given the same list, which is where Micronaut's own resolution reads it from.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NonbindingMembersRemapper implements AnnotationRemapper {

    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";
    private static final String MICRONAUT_NONBINDING = "io.micronaut.context.annotation.NonBinding";

    /**
     * The attribute of the compilation that names the annotation interfaces being read: reading one builds the
     * metadata of its members, which comes back here, and an annotation that names itself there is not read
     * again. The compilation is what the reading belongs to, so the names are kept on it rather than on this class
     * or on the thread, and nothing outlives it. The interface itself is not cached: Micronaut keeps the
     * resolved type and the metadata of its members for the round, and reading the names off them is cheap.
     */
    private static final String READING = NonbindingMembersRemapper.class.getName() + ".reading";

    @Override
    public String getPackageName() {
        return ALL_PACKAGES;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, @Nullable VisitorContext visitorContext) {
        String name = annotation.getAnnotationName();
        if (visitorContext == null || isNeverABindingType(name)) {
            return List.of(annotation);
        }
        DiscoveredClasses discovered = BuildCompatibleExtensionVisitor.discoveryOf(visitorContext);
        Boolean bindingType = isBindingType(annotation, discovered);
        if (Boolean.FALSE.equals(bindingType)) {
            return List.of(annotation);
        }
        return List.of(complete(annotation, nonbindingMembersOf(name, visitorContext, discovered, bindingType == null)));
    }

    /**
     * The qualifier with every member that takes no part in resolution listed under {@code $nonBinding}: what a
     * synthetic component's qualifier, which an extension composes rather than the compiler reading it, is
     * recorded with.
     *
     * @param qualifier  The qualifier
     * @param context    The compilation, which the annotation interface is read from
     * @param discovered What the discovery phase of the compilation said
     * @return The qualifier, the same one where nothing was missing
     */
    public static AnnotationValue<?> complete(AnnotationValue<?> qualifier, VisitorContext context,
                                              @Nullable DiscoveredClasses discovered) {
        String name = qualifier.getAnnotationName();
        if (isNeverABindingType(name)) {
            return qualifier;
        }
        return complete(qualifier, nonbindingMembersOf(name, context, discovered, false));
    }

    private static AnnotationValue<?> complete(AnnotationValue<?> annotation, Set<String> members) {
        if (members.isEmpty()) {
            return annotation;
        }
        Set<String> recorded =
            new LinkedHashSet<>(Arrays.asList(annotation.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)));
        if (recorded.containsAll(members)) {
            return annotation;
        }
        recorded.addAll(members);
        // the attribute lists itself, as Micronaut records it, so that Micronaut's own comparison leaves it out
        recorded.add(AnnotationUtil.NON_BINDING_ATTRIBUTE);
        String[] nonbinding = recorded.toArray(new String[0]);
        AnnotationValueBuilder<?> remapped =
            annotation.mutate().member(AnnotationUtil.NON_BINDING_ATTRIBUTE, nonbinding);
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes != null) {
            for (AnnotationValue<?> stereotype : stereotypes) {
                if (AnnotationUtil.QUALIFIER.equals(stereotype.getAnnotationName())) {
                    remapped.replaceStereotype(stereotype,
                        stereotype.mutate().member(AnnotationUtil.NON_BINDING_ATTRIBUTE, nonbinding).build());
                    break;
                }
            }
        }
        return remapped.build();
    }

    /**
     * The annotations of the language, of Micronaut and of this module, which are never a qualifier with a
     * member an application marks: passed over without reading anything.
     */
    private static boolean isNeverABindingType(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("kotlin.")
            || name.startsWith("groovy.") || name.startsWith("jakarta.inject.") || name.startsWith("jakarta.annotation.")
            || name.startsWith("io.micronaut.context.annotation.") || name.startsWith("io.micronaut.core.annotation.")
            || name.startsWith("io.micronaut.inject.") || name.startsWith("io.micronaut.aop.")
            || name.startsWith("io.micronaut.cdi.internal.metadata.");
    }

    /**
     * Whether the annotation is a qualifier or an interceptor binding, from the meta-annotations Micronaut read
     * into the occurrence and from what the discovery phase registered.
     *
     * @return Whether it is, or {@code null} where the occurrence carries no meta-annotations to say
     */
    private static @Nullable Boolean isBindingType(AnnotationValue<?> annotation, @Nullable DiscoveredClasses discovered) {
        if (discovered != null && discovered.isRegisteredBindingType(annotation.getAnnotationName())) {
            return true;
        }
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes == null) {
            return null;
        }
        for (AnnotationValue<?> stereotype : stereotypes) {
            String name = stereotype.getAnnotationName();
            if (Cdi.QUALIFIER.equals(name) || BINDING.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The members of the annotation that take no part in resolution: the ones its interface marks, and the ones
     * the discovery phase marked.
     *
     * @param askDeclaration Whether the occurrence left it open whether the annotation is a binding type, which
     *                       its declaration then answers
     */
    private static Set<String> nonbindingMembersOf(String name, VisitorContext context,
                                                   @Nullable DiscoveredClasses discovered, boolean askDeclaration) {
        Set<String> members = new LinkedHashSet<>(declaredNonbindingMembersOf(name, context, askDeclaration));
        if (discovered != null) {
            members.addAll(discovered.nonbindingMembersOf(name));
        }
        return members;
    }

    private static Set<String> declaredNonbindingMembersOf(String name, VisitorContext context,
                                                           boolean askDeclaration) {
        Set<String> reading = reading(context);
        if (!reading.add(name)) {
            return Set.of();
        }
        try {
            return readDeclaration(name, context, askDeclaration);
        } finally {
            reading.remove(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reading(VisitorContext context) {
        Set<String> reading = context.get(READING, Set.class).orElse(null);
        if (reading == null) {
            reading = new HashSet<>();
            context.put(READING, reading);
        }
        return reading;
    }

    private static Set<String> readDeclaration(String name, VisitorContext context, boolean askDeclaration) {
        // a nested annotation is recorded by its binary name, and asked of the compiler by its canonical one
        Optional<ClassElement> found = context.getClassElement(name);
        if (found.isEmpty() && name.indexOf('$') >= 0) {
            found = context.getClassElement(name.replace('$', '.'));
        }
        ClassElement declaration = found.orElse(null);
        if (declaration == null) {
            return Set.of();
        }
        if (askDeclaration && !declaration.hasDeclaredAnnotation(Cdi.QUALIFIER)
            && !declaration.hasDeclaredAnnotation(BINDING)) {
            return Set.of();
        }
        Set<String> members = new LinkedHashSet<>();
        membersOf(declaration).forEach((member, metadata) -> {
            if (metadata.hasAnnotation(Cdi.NONBINDING) || metadata.hasAnnotation(MICRONAUT_NONBINDING)) {
                members.add(member);
            }
        });
        return members;
    }

    /**
     * The members an annotation interface declares, each with its annotation metadata.
     *
     * <p>Javac and the Groovy compiler answer them as the methods of the interface. KSP answers an annotation's
     * members as its properties and no methods, a Java annotation on the classpath included, so those are read
     * where there are no methods.</p>
     *
     * @param declaration The annotation interface
     * @return The members, by name, in the order they are declared
     */
    public static Map<String, AnnotationMetadata> membersOf(ClassElement declaration) {
        Map<String, AnnotationMetadata> members = new LinkedHashMap<>();
        for (MethodElement member : declaration.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            members.put(member.getName(), member.getAnnotationMetadata());
        }
        if (members.isEmpty()) {
            for (PropertyElement member : declaration.getBeanProperties()) {
                members.put(member.getName(), member.getAnnotationMetadata());
            }
        }
        return members;
    }
}
