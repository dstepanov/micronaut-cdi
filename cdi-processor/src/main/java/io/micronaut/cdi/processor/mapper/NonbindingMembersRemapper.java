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
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.AnnotationMemberElement;
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
 * Records on every occurrence of a qualifier or an interceptor binding the members a build compatible extension
 * marked {@code Nonbinding} while the compilation was discovering its classes, under {@code $nonBinding}: the one
 * record of the members that take no part in resolution the container compares by.
 *
 * <p>Micronaut lists the members the annotation interface marks {@code NonBinding} on every occurrence itself,
 * written or left at their defaults, and on the qualifier stereotype of the occurrence, which is where its own
 * resolution reads them from. What it cannot know is a member the discovery phase marked, which the source does not:
 * those are merged with what Micronaut wrote, on the occurrence and on its qualifier stereotype. A qualifier an
 * extension composes for a synthetic component is no occurrence Micronaut reads, and is given the members its
 * interface marks as well ({@link #complete(AnnotationValue, VisitorContext, DiscoveredClasses)}).</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NonbindingMembersRemapper implements AnnotationRemapper {

    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";
    private static final String MICRONAUT_NONBINDING = "io.micronaut.context.annotation.NonBinding";

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
        if (discovered == null) {
            return List.of(annotation);
        }
        Set<String> marked = discovered.nonbindingMembersOf(name);
        if (marked.isEmpty() || Boolean.FALSE.equals(isBindingType(annotation, discovered))) {
            return List.of(annotation);
        }
        return List.of(complete(annotation, marked));
    }

    /**
     * The qualifier with every member that takes no part in resolution listed under {@code $nonBinding}: what a
     * synthetic component's qualifier, which an extension composes rather than the compiler reading it, is
     * recorded with. Micronaut lists the members an annotation interface marks only on an occurrence it reads, so
     * they are read off the interface here, and the ones the discovery phase marked are added to them.
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
        Set<String> members = new LinkedHashSet<>(declaredNonbindingMembersOf(name, context));
        if (discovered != null) {
            members.addAll(discovered.nonbindingMembersOf(name));
        }
        return complete(qualifier, members);
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
    private static @Nullable Boolean isBindingType(AnnotationValue<?> annotation, DiscoveredClasses discovered) {
        if (discovered.isRegisteredBindingType(annotation.getAnnotationName())) {
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
     * The members the annotation interface of the given name marks as taking no part in resolution. Reading them
     * builds the metadata of the members, which Micronaut guards against two annotation interfaces whose members are
     * annotated with each other.
     */
    private static Set<String> declaredNonbindingMembersOf(String name, VisitorContext context) {
        // a nested annotation is recorded by its binary name, and asked of the compiler by its canonical one
        Optional<ClassElement> found = context.getClassElement(name);
        if (found.isEmpty() && name.indexOf('$') >= 0) {
            found = context.getClassElement(name.replace('$', '.'));
        }
        ClassElement declaration = found.orElse(null);
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
