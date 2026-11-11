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

import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers;
import io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers.Members;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.metadata.CdiSyntheticObserver;
import io.micronaut.context.BeanContext;
import io.micronaut.context.WatchableBeanContext;
import io.micronaut.context.BeanDefinitionRegistry;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.MethodInjectionPoint;
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
 * type used only where a bean is injected or an event observed is found by reading the injection points and the
 * executable methods of the definitions, the first time a type is asked for that the class level metadata does not
 * know. A portable extension may add a qualifier as the container starts.</p>
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
    private volatile @Nullable Map<String, Recorded> fromClasses;
    private volatile @Nullable Map<String, Recorded> fromMembers;

    /**
     * @param beanContext The context whose beans are read
     */
    public CompiledAnnotationTypes(BeanContext beanContext) {
        this.beanContext = beanContext;
        if (beanContext instanceof WatchableBeanContext watchable) {
            // a bean of the specification registered once the context runs, or retired or added by a development
            // reload, may use a type nothing else was compiled with: what was read is read again on the next
            // question. The watch belongs to this bean, and is closed as it is destroyed
            watchable.definitions().stereotype(CdiScope.class).watch(change -> {
                if (!change.initial()) {
                    forget();
                }
            });
            watchable.classChanges().watch(change -> forget());
        }
    }

    private synchronized void forget() {
        fromClasses = null;
        fromMembers = null;
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
        Recorded recorded = fromClasses().get(name);
        if (recorded != null && recorded.membersKnown) {
            return recorded;
        }
        // a type the classes do not carry, or name alone - as a registration compiled without the members of the
        // annotation does - may be one an injection point or an observer is qualified by
        Recorded occurring = fromMembers().get(name);
        return occurring != null ? occurring : recorded;
    }

    private Map<String, Recorded> fromClasses() {
        Map<String, Recorded> read = fromClasses;
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

    private Map<String, Recorded> fromMembers() {
        Map<String, Recorded> read = fromMembers;
        if (read == null) {
            synchronized (this) {
                read = fromMembers;
                if (read == null) {
                    read = readMembers();
                    fromMembers = read;
                }
            }
        }
        return read;
    }

    private Map<String, Recorded> readClasses() {
        Map<String, Recorded> types = new HashMap<>();
        Collection<BeanDefinitionReference<Object>> references = beanContext instanceof
            BeanDefinitionRegistry registry ? registry.getBeanDefinitionReferences() : List.of();
        for (BeanDefinitionReference<Object> reference : references) {
            AnnotationMetadata metadata = reference.getAnnotationMetadata();
            read(metadata, types);
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
            // the qualifiers of a synthetic component are recorded as values nested in its record
            readNested(metadata.getAnnotation(CdiSyntheticBean.class),
                CdiSyntheticBean.QUALIFIERS, types);
            readNested(metadata.getAnnotation(CdiSyntheticObserver.class),
                CdiSyntheticObserver.QUALIFIERS, types);
        }
        return types;
    }

    private Map<String, Recorded> readMembers() {
        Map<String, Recorded> types = new HashMap<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            for (Argument<?> argument : definition.getConstructor().getArguments()) {
                read(argument.getAnnotationMetadata(), types);
            }
            for (FieldInjectionPoint<?, ?> field : definition.getInjectedFields()) {
                read(field.getAnnotationMetadata(), types);
            }
            for (MethodInjectionPoint<?, ?> method : definition.getInjectedMethods()) {
                for (Argument<?> argument : method.getArguments()) {
                    read(argument.getAnnotationMetadata(), types);
                }
            }
            for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
                read(method.getAnnotationMetadata(), types);
                for (Argument<?> argument : method.getArguments()) {
                    read(argument.getAnnotationMetadata(), types);
                }
            }
        }
        return types;
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
