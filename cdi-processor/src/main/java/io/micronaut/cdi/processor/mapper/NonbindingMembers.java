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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.AnnotationMemberElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The members of a qualifier or an interceptor binding its annotation interface marks {@code Nonbinding}, which take
 * no part in resolution, as the compilation sees the interface.
 *
 * <p>Micronaut lists them on every occurrence it reads itself, under {@code $nonBinding}, written or left at their
 * defaults, and on the qualifier stereotype of the occurrence: it reads the members from the compilation's view of
 * the annotation interface, so a member a build compatible extension marked while the compilation was discovering its
 * classes (on the member, as the discovery phase is applied) is listed as one the source marks is
 * ({@code jakarta.enterprise.util.Nonbinding} being read as Micronaut's {@code NonBinding},
 * {@link NonbindingAnnotationMapper}). A qualifier an extension composes for a synthetic component is no occurrence
 * Micronaut reads, and is given every member its interface marks here, read off the members
 * ({@link #complete(AnnotationValue, VisitorContext)}).</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NonbindingMembers {

    private static final String MICRONAUT_NONBINDING = "io.micronaut.context.annotation.NonBinding";

    private NonbindingMembers() {
    }

    /**
     * The annotation interface of the given name as the compilation holds it.
     */
    private static @Nullable ClassElement declarationOf(String name, VisitorContext context) {
        // a nested annotation is recorded by its binary name, and asked of the compiler by its canonical one
        Optional<ClassElement> found = context.getClassElement(name);
        if (found.isEmpty() && name.indexOf('$') >= 0) {
            found = context.getClassElement(name.replace('$', '.'));
        }
        return found.orElse(null);
    }

    /**
     * The qualifier with every member that takes no part in resolution listed under {@code $nonBinding}: what a
     * synthetic component's qualifier, which an extension composes rather than the compiler reading it, is
     * recorded with. Micronaut lists the members an annotation interface marks only on an occurrence it reads, so
     * they are read off the interface here.
     *
     * @param qualifier The qualifier
     * @param context   The compilation, which the annotation interface is read from
     * @return The qualifier, the same one where nothing was missing
     */
    public static AnnotationValue<?> complete(AnnotationValue<?> qualifier, VisitorContext context) {
        String name = qualifier.getAnnotationName();
        if (isNeverABindingType(name)) {
            return qualifier;
        }
        return complete(qualifier, declaredNonbindingMembersOf(name, context));
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
     * Every member the annotation interface of the given name marks as taking no part in resolution, as the
     * compilation holds the interface: the ones its source marks and the ones the discovery phase marked, read off
     * the members.
     */
    private static Set<String> declaredNonbindingMembersOf(String name, VisitorContext context) {
        ClassElement declaration = declarationOf(name, context);
        if (declaration == null) {
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
     * @param declaration The annotation interface
     * @return The members, by name, in the order they are declared
     */
    public static Map<String, AnnotationMetadata> membersOf(ClassElement declaration) {
        Map<String, AnnotationMetadata> members = new LinkedHashMap<>();
        memberElementsOf(declaration).forEach((name, member) -> members.put(name, member.getAnnotationMetadata()));
        return members;
    }

    /**
     * The members an annotation interface declares, as Micronaut answers them under every compiler
     * ({@link AnnotationElement#getMembers()}).
     *
     * @param declaration The annotation interface
     * @return The members, by name, in the order they are declared
     */
    public static Map<String, AnnotationMemberElement> memberElementsOf(ClassElement declaration) {
        Map<String, AnnotationMemberElement> members = new LinkedHashMap<>();
        if (declaration instanceof AnnotationElement annotation) {
            for (AnnotationMemberElement member : annotation.getMembers()) {
                members.put(member.getName(), member);
            }
        }
        return members;
    }
}
