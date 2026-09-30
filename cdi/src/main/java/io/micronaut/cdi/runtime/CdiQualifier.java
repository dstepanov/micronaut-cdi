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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One qualifier or interceptor binding, as the container compares it: the annotation type and the values of
 * the members that take part in resolution.
 *
 * <p>The specification's interfaces speak of qualifiers as annotation instances. Micronaut records an
 * annotation as the values it was written with, and that is all resolution needs: two qualifiers are the same
 * when they are of one type and their binding members are equal. So a qualifier is held here as values,
 * whether it was compiled onto a bean or an injection point, handed over as an {@link AnnotationValue}, or
 * handed over as an instance. The instance is kept where a program supplied one, and is made - by the module
 * that makes annotation instances - only when a program asks for it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiQualifier {

    /**
     * The name of the reflection-free way to select by a qualifier with members, for the message of a call
     * that needs the reflection module.
     */
    public static final String SELECT_BY_VALUE = "io.micronaut.cdi.MicronautInstance and io.micronaut.cdi."
        + "MicronautEvent select by AnnotationValue, which reads nothing back";

    /**
     * The {@code Any} qualifier.
     */
    public static final CdiQualifier ANY =
        new CdiQualifier(AnnotationValue.builder(Any.class).build(), null, null, Any.Literal.INSTANCE);

    /**
     * The {@code Default} qualifier.
     */
    public static final CdiQualifier DEFAULT =
        new CdiQualifier(AnnotationValue.builder(Default.class).build(), null, null, Default.Literal.INSTANCE);

    private static final String ANY_NAME = "jakarta.enterprise.inject.Any";
    private static final String DEFAULT_NAME = "jakarta.enterprise.inject.Default";
    private static final String NAMED_NAME = "jakarta.inject.Named";

    private final AnnotationValue<?> binding;
    private final @Nullable AnnotationValue<?> written;
    private final @Nullable AnnotationMetadata source;
    private volatile @Nullable Annotation instance;

    private CdiQualifier(AnnotationValue<?> binding, @Nullable AnnotationValue<?> written,
                         @Nullable AnnotationMetadata source, @Nullable Annotation instance) {
        this.binding = binding;
        this.written = written;
        this.source = source;
        this.instance = instance;
    }

    /**
     * The name of the annotation type.
     *
     * @return The name
     */
    public String name() {
        return binding.getAnnotationName();
    }

    /**
     * The members that take part in resolution, with their values.
     *
     * @return The binding values
     */
    public AnnotationValue<?> binding() {
        return binding;
    }

    /**
     * Whether this is the {@code Any} qualifier.
     *
     * @return Whether it is
     */
    public boolean isAny() {
        return ANY_NAME.equals(name());
    }

    /**
     * Whether this is the {@code Default} qualifier.
     *
     * @return Whether it is
     */
    public boolean isDefault() {
        return DEFAULT_NAME.equals(name());
    }

    /**
     * Whether this is a {@code Named} qualifier.
     *
     * @return Whether it is
     */
    public boolean isNamed() {
        return NAMED_NAME.equals(name());
    }

    /**
     * Whether the other qualifier is the same one as far as resolution goes: of the same type, with every
     * binding member equal, a member left at its default being equal to the default written down.
     *
     * @param other The other qualifier
     * @return Whether they are equivalent
     */
    public boolean matches(CdiQualifier other) {
        String name = name();
        if (!name.equals(other.name())) {
            return false;
        }
        if (binding.getValues().isEmpty() && other.binding.getValues().isEmpty()) {
            return true;
        }
        Map<CharSequence, Object> defaults = AnnotationMetadataSupport.getDefaultValues(name);
        return new AnnotationValue<>(name, written(binding.getValues(), defaults), defaults)
            .matches(new AnnotationValue<>(name, written(other.binding.getValues(), defaults), defaults));
    }

    /**
     * The values less the members that hold the empty string or an empty array where no default is recorded
     * for them: compiled metadata leaves an empty default out, so an empty member that was written down and
     * one that was left at its default are told apart by nothing but being there.
     */
    private static Map<CharSequence, Object> written(Map<CharSequence, Object> values,
                                                     Map<CharSequence, Object> defaults) {
        Map<CharSequence, Object> written = null;
        for (Map.Entry<CharSequence, Object> member : values.entrySet()) {
            Object value = member.getValue();
            boolean empty = value instanceof CharSequence text ? text.isEmpty()
                : value instanceof Object[] array && array.length == 0;
            if (empty && !defaults.containsKey(member.getKey().toString())) {
                if (written == null) {
                    written = new LinkedHashMap<>(values);
                }
                written.remove(member.getKey());
            }
        }
        return written == null ? values : written;
    }

    /**
     * Whether any of the given qualifiers is equivalent to this one.
     *
     * @param qualifiers The qualifiers
     * @return Whether one matches
     */
    public boolean isAmong(Collection<CdiQualifier> qualifiers) {
        for (CdiQualifier candidate : qualifiers) {
            if (matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The qualifier as an annotation instance, which is what the specification's interfaces report: the one a
     * program supplied, a literal of the specification, or an instance made by the module that makes them.
     *
     * @return The instance, or {@code null} where the annotation class is not on the classpath
     * @throws UnsupportedOperationException Where an instance has to be made and the reflection module is absent
     */
    public @Nullable Annotation instance() {
        Annotation resolved = instance;
        if (resolved != null) {
            return resolved;
        }
        if (isNamed()) {
            resolved = jakarta.enterprise.inject.literal.NamedLiteral.of(binding.stringValue().orElse(""));
        } else {
            AnnotationMetadata metadata = source == null ? AnnotationMetadata.EMPTY_METADATA : source;
            Class<? extends Annotation> type = metadata.getAnnotationType(name()).orElse(null);
            if (type == null) {
                return null;
            }
            try {
                resolved = CdiAnnotations.annotationOf(type, written == null ? binding : written);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        instance = resolved;
        return resolved;
    }

    /**
     * The instances of the given qualifiers, leaving out one whose annotation class is not on the classpath.
     *
     * @param qualifiers The qualifiers
     * @return The instances
     */
    public static Set<Annotation> instances(Collection<CdiQualifier> qualifiers) {
        Set<Annotation> instances = new LinkedHashSet<>();
        for (CdiQualifier qualifier : qualifiers) {
            Annotation made = qualifier.instance();
            if (made != null) {
                instances.add(made);
            }
        }
        return instances;
    }

    /**
     * A {@code Named} qualifier.
     *
     * @param name The name
     * @return The qualifier
     */
    public static CdiQualifier named(String name) {
        return new CdiQualifier(AnnotationValue.builder(Named.class).value(name).build(), null, null, null);
    }

    /**
     * A qualifier as it was compiled onto an element.
     *
     * @param metadata The metadata of the element, which knows the annotation's class
     * @param written  The annotation as it was written
     * @return The qualifier
     */
    public static CdiQualifier ofCompiled(AnnotationMetadata metadata, AnnotationValue<?> written) {
        String name = written.getAnnotationName();
        if (DEFAULT_NAME.equals(name)) {
            return DEFAULT;
        }
        if (ANY_NAME.equals(name)) {
            return ANY;
        }
        return new CdiQualifier(bindingOf(name, written.getValues(), nonbindingOf(name, metadata, null)),
            written, metadata, null);
    }

    /**
     * A qualifier as it was recorded for a synthetic component, with the members that take no part in
     * resolution named beside it.
     *
     * @param written    The annotation, every member among its values
     * @param nonbinding The members that take no part, each as {@code annotationName#memberName}
     * @param type       The annotation class, where the record refers to it
     * @return The qualifier
     */
    public static CdiQualifier ofRecorded(AnnotationValue<?> written, Set<String> nonbinding,
                                          @Nullable Class<? extends Annotation> type) {
        String name = written.getAnnotationName();
        if (DEFAULT_NAME.equals(name)) {
            return DEFAULT;
        }
        if (ANY_NAME.equals(name)) {
            return ANY;
        }
        Set<String> members = new LinkedHashSet<>(nonbindingOf(name, null, type));
        for (String entry : nonbinding) {
            if (entry.startsWith(name + "#")) {
                members.add(entry.substring(name.length() + 1));
            }
        }
        AnnotationMetadata source = type == null ? null : new KnownType(type);
        return new CdiQualifier(bindingOf(name, written.getValues(), members), written, source, null);
    }

    /**
     * A qualifier a program handed over as the values it is written with.
     *
     * @param value The annotation value
     * @return The qualifier
     */
    public static CdiQualifier ofValue(AnnotationValue<?> value) {
        String name = value.getAnnotationName();
        if (DEFAULT_NAME.equals(name)) {
            return DEFAULT;
        }
        if (ANY_NAME.equals(name)) {
            return ANY;
        }
        return new CdiQualifier(bindingOf(name, value.getValues(), nonbindingOf(name, null, null)), value, null, null);
    }

    /**
     * A qualifier a program handed over as an annotation instance.
     *
     * <p>The qualifiers of the specification are known for what they are, and a qualifier the application was
     * compiled with that has no binding member is told from another of its type by nothing: neither is read.
     * Anything else is read member by member, which is reflection, and is asked of the module that does
     * it.</p>
     *
     * @param annotation The annotation
     * @return The qualifier
     * @throws UnsupportedOperationException Where the annotation has to be read and the reflection module is
     *                                       absent
     */
    public static CdiQualifier ofInstance(Annotation annotation) {
        if (annotation instanceof Any) {
            return ANY;
        }
        if (annotation instanceof Default) {
            return DEFAULT;
        }
        if (annotation instanceof Named named) {
            return new CdiQualifier(AnnotationValue.builder(Named.class).value(named.value()).build(),
                null, null, annotation);
        }
        // the qualifiers the container itself fires its lifecycle events with, read through their own interface
        if (annotation instanceof jakarta.enterprise.context.Initialized initialized) {
            return scoped(jakarta.enterprise.context.Initialized.class, initialized.value(), annotation);
        }
        if (annotation instanceof jakarta.enterprise.context.BeforeDestroyed beforeDestroyed) {
            return scoped(jakarta.enterprise.context.BeforeDestroyed.class, beforeDestroyed.value(), annotation);
        }
        if (annotation instanceof jakarta.enterprise.context.Destroyed destroyed) {
            return scoped(jakarta.enterprise.context.Destroyed.class, destroyed.value(), annotation);
        }
        Class<? extends Annotation> type = annotation.annotationType();
        String name = type.getName();
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        if (recorded != null && recorded.isMarker()) {
            return new CdiQualifier(new AnnotationValue<>(name), null, null, annotation);
        }
        CdiReflection reflection = CdiReflection.current("Reading the members of the annotation instance "
            + annotation + " (" + SELECT_BY_VALUE + ")");
        AnnotationValue<?> values = reflection.valuesOf(annotation);
        return new CdiQualifier(bindingOf(name, values.getValues(), nonbindingOf(name, null, type)),
            values, null, annotation);
    }

    private static CdiQualifier scoped(Class<? extends Annotation> type, Class<? extends Annotation> scope,
                                       Annotation annotation) {
        return new CdiQualifier(AnnotationValue.builder(type)
            .member("value", scope).build(), null, null, annotation);
    }

    /**
     * The qualifiers a program handed over as annotation instances.
     *
     * @param annotations The annotations
     * @return The qualifiers
     */
    public static List<CdiQualifier> ofInstances(Annotation... annotations) {
        List<CdiQualifier> qualifiers = new ArrayList<>(annotations.length);
        for (Annotation annotation : annotations) {
            qualifiers.add(ofInstance(annotation));
        }
        return qualifiers;
    }

    /**
     * The qualifiers a program handed over as annotation instances.
     *
     * @param annotations The annotations
     * @return The qualifiers
     */
    public static List<CdiQualifier> ofInstances(Collection<Annotation> annotations) {
        List<CdiQualifier> qualifiers = new ArrayList<>(annotations.size());
        for (Annotation annotation : annotations) {
            qualifiers.add(ofInstance(annotation));
        }
        return qualifiers;
    }

    /**
     * The qualifiers an element was annotated with, and only those.
     *
     * <p>It is what a bean's qualifiers are read from, and it is also what an observer method observes and what
     * an injected event or lookup is qualified by: in each of those the qualifiers are the ones that were
     * written, with nothing added. {@code Any} itself is kept where it was written, because at an injection
     * point it is the difference between asking for every bean of the type and asking for the default one.</p>
     *
     * @param metadata The metadata of the element
     * @return The qualifiers
     */
    public static List<CdiQualifier> declared(AnnotationMetadata metadata) {
        List<CdiQualifier> qualifiers = new ArrayList<>(2);
        if (metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiAny")) {
            // Any was written here; it is carried as the marker so that Micronaut does not narrow by it
            qualifiers.add(ANY);
        }
        for (AnnotationValue<Annotation> annotation : AnnotationUtil.findQualifierAnnotations(metadata)) {
            // the list may carry a gap where the metadata records a qualifier it has no values for
            if (annotation == null) {
                continue;
            }
            String name = annotation.getAnnotationName();
            if (isMicronautOwn(name)) {
                continue;
            }
            if (NAMED_NAME.equals(name) && metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiName")) {
                // the name came through a stereotype — recorded as CdiName — so the bean has the name, but
                // Named is not among its qualifiers (section 2.6.1). The jakarta annotation beside it is the
                // default Micronaut materialized from the stereotype, not something the author wrote
                continue;
            }
            if (ANY_NAME.equals(name)) {
                if (!qualifiers.contains(ANY)) {
                    qualifiers.add(ANY);
                }
                continue;
            }
            qualifiers.add(ofCompiled(metadata, annotation));
        }
        return qualifiers;
    }

    /**
     * Whether the qualifier is one Micronaut declares on a bean of its own accord rather than one the author
     * wrote, and so is not a qualifier of the bean as far as the specification is concerned.
     */
    private static boolean isMicronautOwn(String name) {
        return "io.micronaut.context.annotation.Primary".equals(name)
            || "io.micronaut.context.annotation.Secondary".equals(name)
            || "io.micronaut.context.annotation.Any".equals(name)
            || "io.micronaut.context.annotation.Type".equals(name);
    }

    /**
     * Whether the annotation type is a qualifier, from what the application was compiled with where that
     * answers, and from the annotation class otherwise.
     *
     * @param type The annotation type
     * @return Whether it is a qualifier
     */
    public static boolean isQualifierType(Class<? extends Annotation> type) {
        Boolean known = isKnownQualifier(type.getName());
        if (known != null) {
            return known;
        }
        return CdiReflection.current("Whether " + type.getName() + ", an annotation the application was not "
            + "compiled with as a qualifier, is one").isAnnotated(type, jakarta.inject.Qualifier.class);
    }

    /**
     * Whether the annotation type of the given name is a qualifier, as far as what the application was compiled
     * with says.
     *
     * @param name The annotation type name
     * @return Whether it is, or {@code null} where nothing was recorded of the type
     */
    public static @Nullable Boolean isKnownQualifier(String name) {
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name)
            || ExtensionQualifiers.isKnownQualifier(name)) {
            return true;
        }
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        return recorded == null ? null : recorded.qualifier();
    }

    /**
     * Checks what the specification requires of the qualifiers a lookup or a selection names: each is a
     * qualifier, and no qualifier type is named twice unless it is repeatable.
     *
     * @param already The qualifiers named before
     * @param given   The qualifiers named now
     * @throws IllegalArgumentException Where one is not a qualifier, or is given twice
     */
    public static void requireWellFormed(Collection<CdiQualifier> already, Collection<CdiQualifier> given) {
        Set<String> seen = new LinkedHashSet<>();
        for (CdiQualifier qualifier : already) {
            seen.add(qualifier.name());
        }
        for (CdiQualifier qualifier : given) {
            String name = qualifier.name();
            Annotation supplied = qualifier.instance;
            Boolean known = isKnownQualifier(name);
            if (known == null && supplied != null) {
                known = isQualifierType(supplied.annotationType());
            }
            if (known == null || !known) {
                throw new IllegalArgumentException(name + " is not a qualifier"
                    + (known == null ? ": the application was not compiled with it as one" : ""));
            }
            if (!seen.add(name) && !isRepeatable(name, supplied)) {
                throw new IllegalArgumentException("The qualifier " + name + " is given twice");
            }
        }
    }

    private static boolean isRepeatable(String name, @Nullable Annotation supplied) {
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        if (recorded != null) {
            return recorded.repeatable();
        }
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name) || supplied == null) {
            return false;
        }
        return CdiReflection.current("Whether the qualifier " + name + ", which the application was not compiled "
            + "with, may be given twice").isAnnotated(supplied.annotationType(), java.lang.annotation.Repeatable.class);
    }

    /**
     * Whether a qualifier type is retained at runtime, which one that qualifies an event has to be.
     *
     * @param qualifier The qualifier
     * @return Whether it is retained
     */
    public static boolean isRetainedAtRuntime(CdiQualifier qualifier) {
        String name = qualifier.name();
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name)) {
            return true;
        }
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        if (recorded != null) {
            return recorded.runtime();
        }
        Annotation supplied = qualifier.instance;
        if (supplied == null) {
            return true;
        }
        return CdiReflection.current("Whether the qualifier " + name + ", which the application was not compiled "
            + "with, is retained at runtime").isRetainedAtRuntime(supplied.annotationType());
    }

    /**
     * The members of an annotation type that take no part in resolution: the ones recorded while the
     * application compiled, the ones an extension excluded, and - for a type nothing was recorded of - the
     * ones the annotation class marks, where the module that reads classes is there to say.
     */
    private static Set<String> nonbindingOf(String name, @Nullable AnnotationMetadata metadata,
                                            @Nullable Class<? extends Annotation> type) {
        BindingTypes.BindingType recorded = BindingTypes.of(name);
        if (recorded != null) {
            return recorded.nonbinding();
        }
        Class<? extends Annotation> resolved = type;
        if (resolved == null && metadata != null) {
            resolved = metadata.getAnnotationType(name).orElse(null);
        }
        if (resolved == null) {
            return Set.of();
        }
        io.micronaut.context.BeanContext context = CdiRunning.currentContext();
        CdiReflection reflection = context == null ? null : context.findBean(CdiReflection.class).orElse(null);
        return reflection == null ? Set.of() : reflection.nonbindingMembersOf(resolved);
    }

    private static AnnotationValue<?> bindingOf(String name, Map<CharSequence, Object> values, Set<String> nonbinding) {
        if (values.isEmpty()) {
            return new AnnotationValue<>(name);
        }
        Map<CharSequence, Object> binding = new LinkedHashMap<>(values);
        binding.keySet().removeIf(member -> {
            String memberName = member.toString();
            return memberName.startsWith("$") || nonbinding.contains(memberName)
                || ExtensionQualifiers.isNonbindingMember(name, memberName);
        });
        return new AnnotationValue<>(name, binding);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CdiQualifier other && matches(other);
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }

    @Override
    public String toString() {
        return "@" + name() + binding.getValues();
    }

    /**
     * The metadata of nothing but one known annotation class: what resolves the class of a recorded qualifier
     * whose record refers to it.
     *
     * @param type The annotation class
     */
    private record KnownType(Class<? extends Annotation> type) implements AnnotationMetadata {

        @Override
        public java.util.Optional<Class<? extends Annotation>> getAnnotationType(String name) {
            return type.getName().equals(name) ? java.util.Optional.of(type) : java.util.Optional.empty();
        }
    }
}
