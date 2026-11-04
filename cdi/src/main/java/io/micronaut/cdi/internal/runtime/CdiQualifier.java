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

import io.micronaut.cdi.internal.metadata.CdiAny;
import io.micronaut.cdi.internal.metadata.CdiName;
import io.micronaut.cdi.internal.metadata.MicronautQualifiers;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        new CdiQualifier(AnnotationValue.builder(Any.class).build(), null, Any.Literal.INSTANCE);

    /**
     * The {@code Default} qualifier.
     */
    public static final CdiQualifier DEFAULT =
        new CdiQualifier(AnnotationValue.builder(Default.class).build(), null, Default.Literal.INSTANCE);

    private static final String ANY_NAME = "jakarta.enterprise.inject.Any";
    private static final String DEFAULT_NAME = "jakarta.enterprise.inject.Default";
    private static final String NAMED_NAME = "jakarta.inject.Named";

    private final AnnotationValue<?> written;
    private final @Nullable AnnotationMetadata source;
    private volatile @Nullable Annotation instance;

    private CdiQualifier(AnnotationValue<?> written, @Nullable AnnotationMetadata source,
                         @Nullable Annotation instance) {
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
        return written.getAnnotationName();
    }

    /**
     * The qualifier with every member it was written or selected with, the ones that do not bind among them and
     * listed under {@code $nonBinding}: what a program reads a non-binding member from, and what Micronaut compares.
     * A qualifier handed over as an annotation instance whose members were not read - one the application was
     * compiled with that has no binding member, without the module that reads an instance - has no members.
     *
     * @return The values of the members
     */
    public AnnotationValue<?> written() {
        return written;
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
     * <p>It is Micronaut's comparison of two annotations, {@link AnnotationValue#matches(AnnotationValue)}, which
     * Micronaut compares an interceptor binding with as well, so that a qualifier and an interceptor binding of one
     * annotation are told apart by the same rules. A member either side lists as non-binding is left out of both:
     * the two lists are of one annotation type and differ only where one side lacked the information - a qualifier
     * a program composed as an {@code AnnotationValue}, or an instance of a type whose member an extension
     * marked - never because the type says two things.</p>
     *
     * @param other The other qualifier
     * @return Whether they are equivalent
     */
    public boolean matches(CdiQualifier other) {
        return written().matches(other.written());
    }

    /**
     * A hash code that two qualifiers which {@link #matches(CdiQualifier) match} share, over the members that
     * bind: what the container answers when asked for the hash code of a qualifier or an interceptor binding.
     *
     * <p>It is Micronaut's hash code for {@link AnnotationValue#matches(AnnotationValue)},
     * {@link AnnotationValue#bindingHashCode()} (since Micronaut Core 5.3, #14194): the binding members at the
     * defaults the value carries, the ones it lists under {@code $nonBinding} left out, an annotation a member holds
     * hashed by its name and an empty string not at all. It agrees with the comparison where each side carries the
     * defaults of its annotation and lists its non-binding members, which a qualifier here does: the container
     * completes one that lacks them with what the application was compiled with as it reads it.</p>
     *
     * @return The hash code
     */
    public int bindingHashCode() {
        return written().bindingHashCode();
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
     * @param beanContext The context of the container asking, whose reflection module makes an instance
     * @return The instance, or {@code null} where the annotation class is not on the classpath
     * @throws UnsupportedOperationException Where an instance has to be made and the reflection module is absent
     */
    public @Nullable Annotation instance(BeanContext beanContext) {
        Annotation resolved = instance;
        if (resolved != null) {
            return resolved;
        }
        if (isNamed()) {
            resolved = NamedLiteral.of(written.stringValue().orElse(""));
        } else {
            AnnotationMetadata metadata = source == null ? AnnotationMetadata.EMPTY_METADATA : source;
            Class<? extends Annotation> type = metadata.getAnnotationType(name()).orElse(null);
            if (type == null) {
                return null;
            }
            try {
                resolved = CdiAnnotations.annotationOf(beanContext, type, written);
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
     * @param beanContext The context of the container asking, whose reflection module makes an instance
     * @param qualifiers  The qualifiers
     * @return The instances
     */
    public static Set<Annotation> instances(BeanContext beanContext,
                                            Collection<CdiQualifier> qualifiers) {
        Set<Annotation> instances = new LinkedHashSet<>();
        for (CdiQualifier qualifier : qualifiers) {
            Annotation made = qualifier.instance(beanContext);
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
        return new CdiQualifier(AnnotationValue.builder(Named.class).value(name).build(), null, null);
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
        return of(written, metadata, null, null);
    }

    /**
     * A qualifier as it was recorded for a synthetic component, its non-binding members listed under
     * {@code $nonBinding} as on a compiled one.
     *
     * @param types   What the application was compiled with of each qualifier type
     * @param written The annotation, every member among its values
     * @param type    The annotation class, where the record refers to it
     * @return The qualifier
     */
    public static CdiQualifier ofRecorded(CompiledAnnotationTypes types, AnnotationValue<?> written,
                                          @Nullable Class<? extends Annotation> type) {
        String name = written.getAnnotationName();
        if (DEFAULT_NAME.equals(name)) {
            return DEFAULT;
        }
        if (ANY_NAME.equals(name)) {
            return ANY;
        }
        return of(written, type == null ? null : new KnownType(type), null, types);
    }

    /**
     * A qualifier a program handed over as the values it is written with.
     *
     * @param types What the application was compiled with of each qualifier type, or {@code null} for nothing
     * @param value The annotation value
     * @return The qualifier
     */
    public static CdiQualifier ofValue(@Nullable CompiledAnnotationTypes types, AnnotationValue<?> value) {
        String name = value.getAnnotationName();
        if (DEFAULT_NAME.equals(name)) {
            return DEFAULT;
        }
        if (ANY_NAME.equals(name)) {
            return ANY;
        }
        return of(value, null, null, types);
    }

    /**
     * A qualifier a program handed over as an annotation instance.
     *
     * <p>The qualifiers of the specification are known for what they are, and a qualifier the application was
     * compiled with that has no binding member is told from another of its type by nothing: neither is read. An
     * instance that carries its values - one the builder generated for a registered annotation type made, or one
     * Micronaut synthesised - hands them over, the members the application was compiled with as non-binding
     * listed beside them. Any other instance of a registered annotation type is read by the builder generated for
     * it, through its accessors; anything else is read by the module that does reflection.</p>
     *
     * @param types      What the application was compiled with
     * @param annotation The annotation
     * @return The qualifier
     * @throws UnsupportedOperationException Where the annotation has to be read and the reflection module is
     *                                       absent
     */
    public static CdiQualifier ofInstance(CompiledAnnotationTypes types, Annotation annotation) {
        if (annotation instanceof Any) {
            return ANY;
        }
        if (annotation instanceof Default) {
            return DEFAULT;
        }
        if (annotation instanceof Named named) {
            return new CdiQualifier(AnnotationValue.builder(Named.class).value(named.value()).build(),
                null, annotation);
        }
        // the qualifiers the container itself fires its lifecycle events with, read through their own interface
        if (annotation instanceof Initialized initialized) {
            return scoped(Initialized.class, initialized.value(), annotation);
        }
        if (annotation instanceof BeforeDestroyed beforeDestroyed) {
            return scoped(BeforeDestroyed.class, beforeDestroyed.value(), annotation);
        }
        if (annotation instanceof Destroyed destroyed) {
            return scoped(Destroyed.class, destroyed.value(), annotation);
        }
        String name = annotation.annotationType().getName();
        if (Boolean.TRUE.equals(types.isMarker(name))) {
            return new CdiQualifier(new AnnotationValue<>(name), null, annotation);
        }
        // named by its type alone: formatting an annotation literal reads every member of it reflectively
        AnnotationValue<?> values = CdiAnnotations.valuesOf(types.beanContext(), annotation,
            "Reading the binding members of a literal of the qualifier " + name + ", which neither carries its values"
                + " nor has a builder generated for its type (register the type with @RegisterAnnotations, or select"
                + " by an AnnotationValue: " + SELECT_BY_VALUE + ")");
        return of(withNonbinding(values, types.nonbindingOf(name)), null, annotation, types);
    }

    /**
     * The values with the given non-binding members listed under {@code $nonBinding} beside the ones they list.
     *
     * <p>Micronaut leaves out the members either side lists, so against a compiled qualifier, which lists them, an
     * instance would compare without them. They are listed here for what compares two instances with each other, and
     * for {@link #bindingHashCode()}, neither of which has a compiled side to take them from.</p>
     */
    private static AnnotationValue<?> withNonbinding(AnnotationValue<?> value, @Nullable Set<String> nonbinding) {
        if (nonbinding == null || nonbinding.isEmpty()) {
            return value;
        }
        Set<String> listed = new LinkedHashSet<>(List.of(value.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)));
        if (listed.containsAll(nonbinding)) {
            return value;
        }
        listed.addAll(nonbinding);
        // the attribute lists itself, as Micronaut records it
        listed.add(AnnotationUtil.NON_BINDING_ATTRIBUTE);
        return value.mutate().member(AnnotationUtil.NON_BINDING_ATTRIBUTE, listed.toArray(new String[0])).build();
    }

    /**
     * The value carrying the defaults of its annotation, which a member left out is compared at: the ones it
     * carries, which compiled metadata hands every value it holds, and otherwise the ones Micronaut registered for
     * the type of a qualifier the application was compiled with, as the metadata the value was read from, or the
     * record of the qualifier types, answers them ({@code AnnotationMetadata.getDefaultValues}).
     *
     * <p>{@link AnnotationValue#matches(AnnotationValue)} takes the defaults of the other side for a value that
     * carries none, as {@code AnnotationValue.builder(Shelf.class).build()} does, so against a compiled qualifier
     * it needs none of its own. It needs them against another value a program built, and {@link #bindingHashCode()}
     * hashes a member left out at its default, so that it is the hash code of the compiled qualifier it matches.</p>
     */
    private static AnnotationValue<?> withDefaults(AnnotationValue<?> value, @Nullable AnnotationMetadata source,
                                                   @Nullable CompiledAnnotationTypes types) {
        Map<CharSequence, Object> carried = value.getDefaultValues();
        if (carried != null && !carried.isEmpty()) {
            return value;
        }
        String name = value.getAnnotationName();
        Map<CharSequence, Object> registered = source != null ? source.getDefaultValues(name) : Map.of();
        if (registered.isEmpty() && types != null) {
            registered = types.defaultsOf(name);
        }
        if (registered.isEmpty()) {
            return value;
        }
        return value.mutate().defaultValues(registered).build();
    }

    private static CdiQualifier scoped(Class<? extends Annotation> type, Class<? extends Annotation> scope,
                                       Annotation annotation) {
        return new CdiQualifier(AnnotationValue.builder(type)
            .member(AnnotationMetadata.VALUE_MEMBER, scope).build(), null, annotation);
    }

    /**
     * The qualifiers a program handed over as annotation instances.
     *
     * @param types       What the application was compiled with
     * @param annotations The annotations
     * @return The qualifiers
     */
    public static List<CdiQualifier> ofInstances(CompiledAnnotationTypes types, Annotation... annotations) {
        List<CdiQualifier> qualifiers = new ArrayList<>(annotations.length);
        for (Annotation annotation : annotations) {
            qualifiers.add(ofInstance(types, annotation));
        }
        return qualifiers;
    }

    /**
     * The qualifiers a program handed over as annotation instances.
     *
     * @param types       What the application was compiled with
     * @param annotations The annotations
     * @return The qualifiers
     */
    public static List<CdiQualifier> ofInstances(CompiledAnnotationTypes types, Collection<Annotation> annotations) {
        List<CdiQualifier> qualifiers = new ArrayList<>(annotations.size());
        for (Annotation annotation : annotations) {
            qualifiers.add(ofInstance(types, annotation));
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
        if (metadata.hasAnnotation(CdiAny.class)) {
            // Any was written here; it is carried as the marker so that Micronaut does not narrow by it
            qualifiers.add(ANY);
        }
        for (AnnotationValue<Annotation> annotation : AnnotationUtil.findQualifierAnnotations(metadata)) {
            // the list may carry a gap where the metadata records a qualifier it has no values for
            if (annotation == null) {
                continue;
            }
            String name = annotation.getAnnotationName();
            if (MicronautQualifiers.isMicronautOwn(name)) {
                continue;
            }
            if (NAMED_NAME.equals(name) && metadata.hasAnnotation(CdiName.class)) {
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
     * Whether the annotation type is a qualifier, from what the application was compiled with where that
     * answers, and from the annotation class otherwise.
     *
     * @param types What the application was compiled with
     * @param type  The annotation type
     * @return Whether it is a qualifier
     */
    public static boolean isQualifierType(CompiledAnnotationTypes types, Class<? extends Annotation> type) {
        Boolean known = isKnownQualifier(types, type.getName());
        if (known != null) {
            return known;
        }
        return CdiReflection.require(types.beanContext(),
            "Whether " + type.getName() + ", an annotation the application was not "
            + "compiled with as a qualifier, is one").isAnnotated(type, Qualifier.class);
    }

    /**
     * Whether the annotation type of the given name is a qualifier, as far as what the application was compiled
     * with says.
     *
     * @param types What the application was compiled with
     * @param name  The annotation type name
     * @return Whether it is, or {@code null} where nothing was compiled of the type
     */
    public static @Nullable Boolean isKnownQualifier(CompiledAnnotationTypes types, String name) {
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name)) {
            return true;
        }
        return types.isQualifier(name);
    }

    /**
     * Checks what the specification requires of the qualifiers a lookup or a selection names: each is a
     * qualifier, and no qualifier type is named twice unless it is repeatable.
     *
     * @param types   What the application was compiled with
     * @param already The qualifiers named before
     * @param given   The qualifiers named now
     * @throws IllegalArgumentException Where one is not a qualifier, or is given twice
     */
    public static void requireWellFormed(CompiledAnnotationTypes types, Collection<CdiQualifier> already,
                                         Collection<CdiQualifier> given) {
        Set<String> seen = new LinkedHashSet<>();
        for (CdiQualifier qualifier : already) {
            seen.add(qualifier.name());
        }
        for (CdiQualifier qualifier : given) {
            String name = qualifier.name();
            Annotation supplied = qualifier.instance;
            Boolean known = isKnownQualifier(types, name);
            if (known == null && supplied != null) {
                known = isQualifierType(types, supplied.annotationType());
            }
            if (known == null || !known) {
                throw new IllegalArgumentException(name + " is not a qualifier"
                    + (known == null ? ": the application was not compiled with it as one" : ""));
            }
            if (!seen.add(name) && !isRepeatable(types, name, supplied)) {
                throw new IllegalArgumentException("The qualifier " + name + " is given twice");
            }
        }
    }

    private static boolean isRepeatable(CompiledAnnotationTypes types, String name, @Nullable Annotation supplied) {
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name)) {
            return false;
        }
        Boolean recorded = types.isRepeatable(name);
        if (recorded != null) {
            return recorded;
        }
        if (supplied == null) {
            return false;
        }
        return CdiReflection.require(types.beanContext(),
            "Whether the qualifier " + name + ", which the application was not compiled "
            + "with, may be given twice").isAnnotated(supplied.annotationType(), Repeatable.class);
    }

    /**
     * Whether a qualifier type is retained at runtime, which one that qualifies an event has to be.
     *
     * @param types     What the application was compiled with
     * @param qualifier The qualifier
     * @return Whether it is retained
     */
    public static boolean isRetainedAtRuntime(CompiledAnnotationTypes types, CdiQualifier qualifier) {
        String name = qualifier.name();
        if (DEFAULT_NAME.equals(name) || ANY_NAME.equals(name) || NAMED_NAME.equals(name)) {
            return true;
        }
        Boolean recorded = types.isRetainedAtRuntime(name);
        if (recorded != null) {
            return recorded;
        }
        Annotation supplied = qualifier.instance;
        if (supplied == null) {
            return true;
        }
        return CdiReflection.require(types.beanContext(),
            "Whether the qualifier " + name + ", which the application was not compiled "
            + "with, is retained at runtime").isRetainedAtRuntime(supplied.annotationType());
    }

    /**
     * A qualifier of the values it was written with, the members it lists under {@code $nonBinding} taking no
     * part in resolution.
     */
    private static CdiQualifier of(AnnotationValue<?> given, @Nullable AnnotationMetadata source,
                                   @Nullable Annotation instance, @Nullable CompiledAnnotationTypes types) {
        return new CdiQualifier(withDefaults(given, source, types), source, instance);
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
        return "@" + name() + written.getValues();
    }

    /**
     * The metadata of nothing but one known annotation class: what resolves the class of a recorded qualifier
     * whose record refers to it.
     *
     * @param type The annotation class
     */
    private record KnownType(Class<? extends Annotation> type) implements AnnotationMetadata {

        @Override
        public Optional<Class<? extends Annotation>> getAnnotationType(String name) {
            return type.getName().equals(name) ? Optional.of(type) : Optional.empty();
        }
    }
}
