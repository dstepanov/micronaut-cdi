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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.metadata.CdiAnnotationTypes;
import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers;
import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers.Members;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.metadata.CdiSyntheticObserver;
import io.micronaut.context.BeanContext;
import io.micronaut.context.WatchableBeanContext;
import io.micronaut.context.BeanDefinitionRegistry;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the application was compiled with of each qualifier and interceptor binding type, read from the compiled
 * metadata of its beans: which annotations are qualifiers or bindings, the members of each and which of them take
 * no part in resolution, whether it may be repeated and whether it is retained at runtime.
 *
 * <p>The specification asks these of an annotation type - {@code BeanContainer.isQualifier}, the rule that a
 * selection names qualifiers and names one that is not repeatable once - and its interfaces hand the container the
 * annotation class to ask them of, which is a question for reflection. Every qualifier and binding the beans of the
 * application use was compiled into their metadata, with its members, the members that do not bind, its retention
 * and its repeatable container, so that is what is asked first; reflection is left to a type nothing was compiled
 * with, through the module that does it.</p>
 *
 * <p>The record is built once per container, from the class level metadata of every bean definition - which reads
 * no definition - and the records of the synthetic components and of the qualifiers an extension registered. A
 * type used only where a bean is injected or an event observed is known from the class level metadata as well: the
 * processor records on each class the qualifier and binding types its members use ({@link CdiAnnotationTypes}). A
 * type only a bean compiled without the processor uses that way is not known here, and is left to the module that
 * reads classes. A portable extension may add a qualifier as the container starts.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class CompiledAnnotationTypes {

    private static final String QUALIFIER = AnnotationUtil.QUALIFIER;
    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";
    private static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";
    private static final String MEMBERS = CdiExtensionQualifiers.MEMBERS;

    private final BeanContext beanContext;
    /**
     * The annotations portable extensions made qualifiers, replaced as a whole by each addition: they are added
     * while the extensions run and read by every question asked of a qualifier, without a lock.
     */
    private volatile Set<String> added = Set.of();
    private volatile @Nullable Classes fromClasses;

    /**
     * @param beanContext The context whose beans are read
     */
    public CompiledAnnotationTypes(BeanContext beanContext) {
        this.beanContext = beanContext;
        if (beanContext instanceof WatchableBeanContext watchable) {
            // a definition registered once the context runs, or retired or added by a development reload, may use
            // a type nothing else was compiled with - one of Micronaut's own as well as one of the specification,
            // since both are read: what was read is read again on the next question. The watch belongs to this
            // bean, and is closed as it is destroyed
            watchable.definitions().watch(change -> {
                if (!change.initial()) {
                    forget();
                }
            });
            watchable.classChanges().watch(change -> forget());
        }
    }

    private synchronized void forget() {
        fromClasses = null;
    }

    /**
     * The context whose beans are read, which is also the container that answers for this record where the
     * application was not compiled with a type: the module that reads classes is one of its beans.
     *
     * @return The bean context
     */
    public BeanContext beanContext() {
        return beanContext;
    }

    /**
     * The record of the given context.
     *
     * @param beanContext The context
     * @return Its record
     */
    public static CompiledAnnotationTypes of(BeanContext beanContext) {
        return beanContext.getBean(CompiledAnnotationTypes.class);
    }

    /**
     * Makes an annotation a qualifier for as long as this container runs, as a portable extension may ask.
     *
     * @param annotationName The name of the annotation
     */
    public synchronized void addQualifier(String annotationName) {
        Set<String> qualifiers = added;
        if (qualifiers.contains(annotationName)) {
            return;
        }
        Set<String> all = new HashSet<>(qualifiers);
        all.add(annotationName);
        added = Set.copyOf(all);
    }

    /**
     * Whether the annotation type of the given name is a qualifier, as far as what the application was compiled
     * with says.
     *
     * @param name The annotation type name
     * @return Whether it is, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Boolean isQualifier(String name) {
        if (added.contains(name)) {
            return true;
        }
        Recorded recorded = recorded(name);
        return recorded == null ? null : recorded.qualifier;
    }

    /**
     * Whether the annotation type of the given name is a stereotype, as far as the class metadata of the beans
     * the application was compiled with says.
     *
     * @param name The annotation type name
     * @return {@code true} where a bean carries it as a stereotype, or {@code null} where no bean does
     */
    public @Nullable Boolean isStereotype(String name) {
        // a stereotype is read off the class metadata of the beans that carry it; one no bean carries is not known
        return fromClasses().stereotypes().contains(name) ? Boolean.TRUE : null;
    }

    /**
     * Whether the annotation type of the given name is an interceptor binding, as far as what the application was
     * compiled with says.
     *
     * @param name The annotation type name
     * @return Whether it is, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Boolean isInterceptorBinding(String name) {
        Recorded recorded = recorded(name);
        return recorded == null ? null : recorded.binding;
    }

    /**
     * Whether the annotation type of the given name may be given more than once: whether Micronaut registered a
     * container for it, as the metadata of every bean that uses it registers as it loads.
     *
     * @param name The annotation type name
     * @return Whether it may, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Boolean isRepeatable(String name) {
        Recorded recorded = recorded(name);
        return recorded == null ? null : AnnotationMetadataSupport.getRepeatableAnnotation(name) != null;
    }

    /**
     * Whether the annotation type of the given name is retained at runtime.
     *
     * @param name The annotation type name
     * @return Whether it is, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Boolean isRetainedAtRuntime(String name) {
        Recorded recorded = recorded(name);
        return recorded == null || recorded.retention == null ? null : recorded.retention == RetentionPolicy.RUNTIME;
    }

    /**
     * Whether an annotation of the type of the given name is told from another of its type by nothing: every
     * member it has takes no part in resolution, so an instance of it need not be read.
     *
     * @param name The annotation type name
     * @return Whether it has no binding member, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Boolean isMarker(String name) {
        Recorded recorded = recorded(name);
        // a name with nothing read of its members is no evidence of an annotation without any
        return recorded == null || !recorded.membersKnown ? null : recorded.nonbinding.containsAll(recorded.members);
    }

    /**
     * The members of the annotation type of the given name that take no part in resolution, as the qualifiers
     * and bindings the application was compiled with list them under {@code $nonBinding}.
     *
     * @param name The annotation type name
     * @return The members, or {@code null} where nothing was compiled of the type
     */
    public @Nullable Set<String> nonbindingOf(String name) {
        Recorded recorded = recorded(name);
        return recorded == null ? null : Set.copyOf(recorded.nonbinding);
    }

    /**
     * The defaults of the members of an annotation type the application was compiled with, as Micronaut registered
     * them, which the metadata of a bean that uses it answers.
     *
     * @param name The name of the annotation type
     * @return The defaults, none where the build recorded nothing of it
     */
    public Map<CharSequence, Object> defaultsOf(String name) {
        Recorded recorded = recorded(name);
        return recorded == null ? Map.of() : AnnotationMetadataSupport.getDefaultValues(name);
    }

    private @Nullable Recorded recorded(String name) {
        return fromClasses().types().get(name);
    }

    private Classes fromClasses() {
        Classes read = fromClasses;
        if (read == null) {
            synchronized (this) {
                read = fromClasses;
                if (read == null) {
                    read = readClasses();
                    fromClasses = read;
                }
            }
        }
        return read;
    }

    private Classes readClasses() {
        Map<String, Recorded> types = new HashMap<>();
        Set<String> stereotypes = new HashSet<>();
        Collection<BeanDefinitionReference<Object>> references = beanContext instanceof
            BeanDefinitionRegistry registry ? registry.getBeanDefinitionReferences() : List.of();
        for (BeanDefinitionReference<Object> reference : references) {
            AnnotationMetadata metadata = reference.getAnnotationMetadata();
            read(metadata, types);
            stereotypes.addAll(metadata.getAnnotationNamesByStereotype(STEREOTYPE));
            AnnotationValue<CdiExtensionQualifiers> registered =
                metadata.getAnnotation(CdiExtensionQualifiers.class);
            if (registered != null) {
                // an annotation an extension made a qualifier of, which no bean may use: its registration is
                // the one witness of it, with the members the compilation saw its interface declare
                for (String qualifier : registered.stringValues()) {
                    types.computeIfAbsent(qualifier, Recorded::named).qualifier = true;
                }
                for (AnnotationValue<Annotation> members : registered.getAnnotations(MEMBERS)) {
                    String qualifier = members.stringValue(Members.NAME).orElse(null);
                    if (qualifier != null) {
                        types.computeIfAbsent(qualifier, Recorded::new)
                            .declared(members.stringValues(MEMBERS), members.stringValues(Members.NONBINDING));
                    }
                }
            }
            // the qualifier and binding types the members of a class compiled with the processor use, which its
            // definition need not be loaded to read
            readUsed(metadata.getAnnotation(CdiAnnotationTypes.class), types);
            // the qualifiers of a synthetic component are recorded as values nested in its record
            readNested(metadata.getAnnotation(CdiSyntheticBean.class),
                CdiSyntheticBean.QUALIFIERS, types);
            readNested(metadata.getAnnotation(CdiSyntheticObserver.class),
                CdiSyntheticObserver.QUALIFIERS, types);
        }
        return new Classes(types, Set.copyOf(stereotypes));
    }

    private static void readUsed(@Nullable AnnotationValue<CdiAnnotationTypes> used, Map<String, Recorded> types) {
        if (used == null) {
            return;
        }
        Set<String> runtime = Set.of(used.stringValues(CdiAnnotationTypes.RUNTIME));
        for (String qualifier : used.stringValues(CdiAnnotationTypes.QUALIFIERS)) {
            types.computeIfAbsent(qualifier, Recorded::named).qualifier = true;
        }
        for (String binding : used.stringValues(CdiAnnotationTypes.BINDINGS)) {
            types.computeIfAbsent(binding, Recorded::named).binding = true;
        }
        // what the compilation saw of the interface: its members and its retention; a type whose interface it did
        // not see is known by its name alone
        for (AnnotationValue<Annotation> members : used.getAnnotations(MEMBERS)) {
            String type = members.stringValue(Members.NAME).orElse(null);
            if (type != null) {
                types.computeIfAbsent(type, Recorded::new)
                    .used(runtime)
                    .declared(members.stringValues(MEMBERS), members.stringValues(Members.NONBINDING));
            }
        }
    }

    private static void readNested(@Nullable AnnotationValue<?> record, String member,
                                   Map<String, Recorded> types) {
        if (record == null) {
            return;
        }
        for (AnnotationValue<Annotation> qualifier : record.getAnnotations(member)) {
            Recorded recorded = types.computeIfAbsent(qualifier.getAnnotationName(), Recorded::new);
            recorded.qualifier = true;
            recorded.read(qualifier);
        }
    }

    private static void read(AnnotationMetadata metadata, Map<String, Recorded> types) {
        if (metadata.isEmpty()) {
            return;
        }
        // an annotation that only carries a qualifier or a binding - a stereotype of the specification - is not one
        List<String> stereotypes = metadata.getAnnotationNamesByStereotype(STEREOTYPE);
        for (AnnotationValue<Annotation> qualifier : AnnotationUtil.findQualifierAnnotations(metadata)) {
            // the list may carry a gap where the metadata records a qualifier it has no values for
            if (qualifier != null && !stereotypes.contains(qualifier.getAnnotationName())) {
                Recorded recorded = types.computeIfAbsent(qualifier.getAnnotationName(), Recorded::new);
                recorded.qualifier = true;
                recorded.read(qualifier);
            }
        }
        for (String name : metadata.getAnnotationNamesByStereotype(BINDING)) {
            if (stereotypes.contains(name)) {
                continue;
            }
            Recorded recorded = types.computeIfAbsent(name, Recorded::new);
            recorded.binding = true;
            for (AnnotationValue<Annotation> binding : metadata.getAnnotationValuesByName(name)) {
                recorded.read(binding);
            }
        }
    }

    /**
     * What the class level metadata of the beans says.
     *
     * @param types       The qualifiers and bindings, by name
     * @param stereotypes The names of the stereotypes the beans carry
     */
    private record Classes(Map<String, Recorded> types, Set<String> stereotypes) {
    }

    /**
     * What was read of one annotation type, accumulated over every occurrence of it.
     */
    private static final class Recorded {

        private final String name;
        private final Set<String> members = new LinkedHashSet<>();
        private final Set<String> nonbinding = new LinkedHashSet<>();
        private boolean qualifier;
        private boolean binding;
        /**
         * Whether the members are known: false only for an annotation an extension's registration names and
         * nothing else was read of - no occurrence, nor the interface as the compilation saw it - since the name
         * alone says nothing of them.
         */
        private boolean membersKnown = true;
        private @Nullable RetentionPolicy retention;

        Recorded(String name) {
            this.name = name;
        }

        /**
         * The record of an annotation known by its name alone, so far.
         */
        static Recorded named(String name) {
            Recorded recorded = new Recorded(name);
            recorded.membersKnown = false;
            return recorded;
        }

        /**
         * Whether the type is retained at runtime, as the processor recorded it from the interface the compilation
         * saw, unless an occurrence said so already.
         */
        Recorded used(Set<String> runtime) {
            if (retention == null) {
                retention = runtime.contains(name) ? RetentionPolicy.RUNTIME : RetentionPolicy.CLASS;
            }
            return this;
        }

        /**
         * The members the compilation saw the interface declare, and the ones that take no part in resolution.
         */
        void declared(String[] declared, String[] declaredNonbinding) {
            membersKnown = true;
            members.addAll(List.of(declared));
            nonbinding.addAll(List.of(declaredNonbinding));
        }

        void read(AnnotationValue<?> occurrence) {
            membersKnown = true;
            // only the names Micronaut keeps for itself among the values are no members: Java lets a member be
            // named with a leading dollar sign
            for (CharSequence member : occurrence.getValues().keySet()) {
                if (!CdiAnnotations.isReservedMember(member)) {
                    members.add(member.toString());
                }
            }
            for (String member : occurrence.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)) {
                if (!CdiAnnotations.isReservedMember(member)) {
                    nonbinding.add(member);
                }
            }
            // a member left at its default is written nowhere, and is a member all the same: Micronaut registered
            // the defaults of the type as the metadata that uses it loaded
            for (CharSequence member : AnnotationMetadataSupport.getDefaultValues(name).keySet()) {
                members.add(member.toString());
            }
            if (retention == null) {
                retention = occurrence.getRetentionPolicy();
            }
        }
    }
}
