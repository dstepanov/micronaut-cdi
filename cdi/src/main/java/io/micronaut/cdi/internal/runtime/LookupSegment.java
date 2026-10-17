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

import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.InjectionPoint;
import org.jspecify.annotations.Nullable;

/**
 * The segment of the resolution path a dependent instance obtained through a lookup is created under: the
 * injection point of the lookup, which section 2.5.2.5 gives the instance as its metadata - the point the lookup
 * was injected into, as the lookup narrowed it, or the lookup itself where it was injected nowhere, with the type
 * and the qualifiers it requires (section 2.4.5.7).
 *
 * <p>The path says where the instance is going, whichever thread the lookup is made on, and nothing is kept beside
 * it: the metadata bean of the specification reads the lookup's point from the segment above the bean's own, and
 * Micronaut reads the segment as the injection point the bean is created for - for its own {@link InjectionPoint},
 * and for the creation context of a synthetic bean. As an injection point of Micronaut's the segment is the member
 * the lookup was injected into, declared by the bean it belongs to, and asks for the type the lookup selected; a
 * lookup injected nowhere is declared by the bean container, the lookup every other one injected nowhere is
 * selected from.</p>
 *
 * @param <B> The type that declares the injection point
 * @param <T> The type the lookup asks for
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class LookupSegment<B, T> implements BeanResolutionContext.Segment<B, T>, InjectionPoint<B> {

    private final BeanDefinition<B> declaringType;
    private final @Nullable Qualifier<B> declaringTypeQualifier;
    private final String name;
    private final AnnotationMetadata annotationMetadata;
    private final Argument<T> asked;
    private final jakarta.enterprise.inject.spi.InjectionPoint selected;

    private LookupSegment(BeanDefinition<B> declaringType, @Nullable Qualifier<B> declaringTypeQualifier, String name,
                          AnnotationMetadata annotationMetadata, Argument<T> asked,
                          jakarta.enterprise.inject.spi.InjectionPoint selected) {
        this.declaringType = declaringType;
        this.declaringTypeQualifier = declaringTypeQualifier;
        this.name = name;
        this.annotationMetadata = annotationMetadata;
        this.asked = asked;
        this.selected = selected;
    }

    /**
     * The segment of a lookup that was injected, at the segment of the point it was injected into.
     *
     * @param injectedAt The segment of the injection point the lookup was injected into
     * @param asked      The type the lookup asks for
     * @param selected   That injection point as the lookup was narrowed
     * @param <B>        The type that declares the injection point
     * @param <T>        The type the lookup asks for
     * @return The segment
     */
    static <B, T> LookupSegment<B, T> injected(BeanResolutionContext.Segment<B, ?> injectedAt, Argument<T> asked,
                                               jakarta.enterprise.inject.spi.InjectionPoint selected) {
        return new LookupSegment<>(injectedAt.getDeclaringType(), injectedAt.getDeclaringTypeQualifier(),
            injectedAt.getName(), injectedAt.getInjectionPoint().getAnnotationMetadata(), asked, selected);
    }

    /**
     * The segment of a lookup injected nowhere, which stands for itself.
     *
     * @param container The definition of the bean container
     * @param asked     The type the lookup asks for
     * @param selected  The lookup as an injection point
     * @param <B>       The type of the bean container
     * @param <T>       The type the lookup asks for
     * @return The segment
     */
    static <B, T> LookupSegment<B, T> standalone(BeanDefinition<B> container, Argument<T> asked,
                                                 jakarta.enterprise.inject.spi.InjectionPoint selected) {
        return new LookupSegment<>(container, null, asked.getName(), AnnotationMetadata.EMPTY_METADATA, asked,
            selected);
    }

    /**
     * @return The injection point of the specification the lookup stands for
     */
    jakarta.enterprise.inject.spi.InjectionPoint selected() {
        return selected;
    }

    @Override
    public BeanDefinition<B> getDeclaringType() {
        return declaringType;
    }

    @Override
    public @Nullable Qualifier<B> getDeclaringTypeQualifier() {
        return declaringTypeQualifier;
    }

    @Override
    public InjectionPoint<B> getInjectionPoint() {
        return this;
    }

    @Override
    public BeanDefinition<B> getDeclaringBean() {
        return declaringType;
    }

    @Override
    public @Nullable Qualifier<B> getDeclaringBeanQualifier() {
        return declaringTypeQualifier;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Argument<T> getArgument() {
        return asked;
    }

    @Override
    public String toString() {
        return "lookup of " + asked.getTypeName() + " at " + selected;
    }
}
