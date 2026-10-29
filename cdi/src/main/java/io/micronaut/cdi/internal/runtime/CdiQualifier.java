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
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueProvider;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
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
     * <p>Micronaut has no hash code for {@link AnnotationValue#matches(AnnotationValue)}. The container hashes an
     * annotation instance, whose non-binding members are the ones the application was compiled with for its type
     * whichever instance it is, and the specification asks two qualifiers that differ by a binding member to hash
     * differently. So the members that bind are hashed, the ones the value leaves out at the defaults it carries;
     * an annotation held by a member is hashed by its name alone, since it may itself list non-binding members, and
     * a member that is an empty string not at all, which only lets more qualifiers share a hash code.</p>
     *
     * @return The hash code
     */
    public int bindingHashCode() {
        AnnotationValue<?> value = written();
        Set<String> nonBinding = Set.of(value.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE));
        Map<CharSequence, Object> members = new LinkedHashMap<>();
        Map<CharSequence, Object> defaults = value.getDefaultValues();
        if (defaults != null) {
            defaults.forEach((member, memberValue) -> members.put(member.toString(), memberValue));
        }
        value.getValues().forEach((member, memberValue) -> members.put(member.toString(), memberValue));
        int hash = name().hashCode();
        for (Map.Entry<CharSequence, Object> member : members.entrySet()) {
            Object memberValue = member.getValue();
            String memberName = member.getKey().toString();
            if (memberValue == null || isMicronautMember(memberName) || nonBinding.contains(memberName)
                || memberValue instanceof String text && text.isEmpty()) {
                continue;
            }
            hash += memberName.hashCode() ^ hashOf(memberValue);
        }
        return hash;
    }

    /**
     * Whether the member is one Micronaut keeps beside the annotation's own: the list of the non-binding members,
     * or the stereotypes.
     */
    private static boolean isMicronautMember(String member) {
        return AnnotationUtil.NON_BINDING_ATTRIBUTE.equals(member) || AnnotationUtil.STEREOTYPES_MEMBER.equals(member);
    }

    /**
     * The hash code of a member value: an array or a collection by its elements, which Micronaut compares an
     * annotation held in either by, a class by its name and a nested annotation by its name. The array types are
     * listed out rather than read through {@code java.lang.reflect.Array}, which keeps it free of reflection.
     */
    private static int hashOf(@Nullable Object value) {
        return switch (value) {
            case null -> 0;
            case AnnotationValue<?> nested -> nested.getAnnotationName().hashCode();
            case AnnotationClassValue<?> type -> type.getName().hashCode();
            case Object[] array -> {
                int hash = 1;
                for (Object element : array) {
                    hash = 31 * hash + hashOf(element);
                }
                yield hash;
            }
            case Collection<?> collection -> {
                int hash = 1;
                for (Object element : collection) {
                    hash = 31 * hash + hashOf(element);
                }
                yield hash;
            }
            case int[] array -> Arrays.hashCode(array);
            case long[] array -> Arrays.hashCode(array);
            case double[] array -> Arrays.hashCode(array);
            case boolean[] array -> Arrays.hashCode(array);
            case byte[] array -> Arrays.hashCode(array);
            case short[] array -> Arrays.hashCode(array);
            case char[] array -> Arrays.hashCode(array);
            case float[] array -> Arrays.hashCode(array);
            default -> value.hashCode();
        };
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
        return of(recorded(value), null, null, types);
    }

    /**
     * The value with its members spelt as Micronaut records them: an enum constant as its name, a class as an
     * {@link AnnotationClassValue}, an annotation it holds spelt so as well.
     *
     * <p>{@code AnnotationValueBuilder.member(String, Enum)} keeps the constant, where compiled metadata holds its
     * name, and {@link AnnotationValue#matches(AnnotationValue)} compares the two as different values, so a value a
     * program built of a qualifier with an enum member would select nothing. The value is left as it is where it
     * holds nothing to respell.</p>
     */
    private static AnnotationValue<?> recorded(AnnotationValue<?> value) {
        Map<CharSequence, Object> values = value.getValues();
        Map<CharSequence, Object> respelt = null;
        for (Map.Entry<CharSequence, Object> member : values.entrySet()) {
            Object given = member.getValue();
            Object spelt = recorded(given);
            if (spelt != given) {
                if (respelt == null) {
                    respelt = new LinkedHashMap<>(values);
                }
                respelt.put(member.getKey(), spelt);
            }
        }
        return respelt == null ? value : new AnnotationValue<>(value.getAnnotationName(), respelt,
            value.getDefaultValues());
    }

    private static @Nullable Object recorded(@Nullable Object value) {
        return switch (value) {
            case Enum<?> constant -> constant.name();
            case Class<?> type -> new AnnotationClassValue<>(type.getName());
            case AnnotationValue<?> nested -> recorded(nested);
            case Object[] array -> {
                Object[] spelt = null;
                for (int i = 0; i < array.length; i++) {
                    Object element = recorded(array[i]);
                    if (element != array[i]) {
                        if (spelt == null) {
                            // of the elements' new type: an Enum[] does not hold a name, nor a Class[] an
                            // AnnotationClassValue
                            spelt = Arrays.copyOf(array, array.length, Object[].class);
                        }
                        spelt[i] = element;
                    }
                }
                yield spelt == null ? array : spelt;
            }
            case null, default -> value;
        };
    }

    /**
     * A qualifier a program handed over as an annotation instance.
     *
     * <p>The qualifiers of the specification are known for what they are, and a qualifier the application was
     * compiled with that has no binding member is told from another of its type by nothing: neither is read. An
     * instance that carries its values - one the builder generated for a registered annotation type made, or one
     * Micronaut synthesised - hands them over, the members the application was compiled with as non-binding
     * listed beside them. Anything else is read member by member, which is reflection, and is asked of the module
     * that does it.</p>
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
        if (annotation instanceof AnnotationValueProvider<?> provider) {
            return of(withNonbinding(provider.annotationValue(), types.nonbindingOf(name)), null, annotation, types);
        }
        CdiReflection reflection = CdiReflection.require(types.beanContext(),
            "Reading the members of the annotation instance "
            + annotation + " (" + SELECT_BY_VALUE + ")");
        return of(reflection.valuesOf(annotation), null, annotation, types);
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
            .member("value", scope).build(), null, annotation);
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
        return new CdiQualifier(withoutUndefaultedEmptyStrings(withDefaults(given, source, types)), source, instance);
    }

    /**
     * The value without the members bound to the empty string that it has no default for.
     *
     * <p>Micronaut records no default of the empty string for an annotation compiled from Java, so the qualifier
     * {@code @Zone} compiled onto a bean, whose member defaults to {@code ""}, carries neither the member nor its
     * default, and {@link AnnotationValue#matches(AnnotationValue)} compares {@code @Zone("")} - a literal, or a value
     * a program built - as a member the bean lacks. The two are the same annotation instance, so a member bound to
     * the empty string with no default to say otherwise is the member left out. Where a default is known, the empty
     * string is compared with it as Micronaut compares it: {@code @Level("")} is not {@code @Level} when the member
     * defaults to {@code "INFO"}.</p>
     */
    private static AnnotationValue<?> withoutUndefaultedEmptyStrings(AnnotationValue<?> value) {
        Map<CharSequence, Object> values = value.getValues();
        Map<CharSequence, Object> defaults = value.getDefaultValues();
        Map<CharSequence, Object> kept = null;
        for (Map.Entry<CharSequence, Object> member : values.entrySet()) {
            if (member.getValue() instanceof String text && text.isEmpty()
                && (defaults == null || !defaults.containsKey(member.getKey()))) {
                if (kept == null) {
                    kept = new LinkedHashMap<>(values);
                }
                kept.remove(member.getKey());
            }
        }
        return kept == null ? value : new AnnotationValue<>(value.getAnnotationName(), kept, defaults);
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
