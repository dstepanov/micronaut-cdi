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

import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentCoercible;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.InstantiatableBeanDefinition;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The base of the two beans of this module that are what the injection point asked for rather than a bean of a
 * type of their own: the event a program fires, and the lookup it resolves beans through.
 *
 * <p>Both are parameterized by the type at the injection point and qualified by its qualifiers, which means
 * neither can be a bean definition Micronaut generates from a class: there is no one {@code Event<T>} to generate,
 * and the bean has to be built when it is injected rather than before. Micronaut has a way of writing such a bean
 * by hand — a bean definition that is its own reference and reads the injection point as it instantiates — and
 * this is that, with the reading of the injection point done once for both.</p>
 *
 * @param <B> The type of the bean produced
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public abstract class CdiInjectionPointFactory<B>
    implements InstantiatableBeanDefinition<B>, BeanDefinitionReference<B>,
    io.micronaut.inject.DisposableBeanDefinition<B> {

    private static final Argument<Object> TYPE_VARIABLE = Argument.ofTypeVariable(Object.class, "T");

    private final MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();

    protected CdiInjectionPointFactory() {
        this(false);
    }

    /**
     * @param mayBuildNothing Whether the bean may legitimately be null — the injection point metadata of an
     *                        object that is not being injected anywhere is null, and the specification says to
     *                        inject it that way
     */
    protected CdiInjectionPointFactory(boolean mayBuildNothing) {
        if (mayBuildNothing) {
            annotationMetadata.addDeclaredAnnotation(
                io.micronaut.core.annotation.AnnotationUtil.NULLABLE, java.util.Map.of());
        }
    }

    /**
     * What one of these beans created on a program's behalf is let go of when the bean itself is: the bean is a
     * dependent of whoever it was injected into, and closing it closes what it made.
     *
     * @param context The bean context
     * @param bean    The bean
     * @return The bean
     */
    @Override
    public final B dispose(@Nullable BeanResolutionContext resolutionContext,
                           io.micronaut.context.BeanContext context, B bean) {
        return dispose(context, bean);
    }

    @Override
    public final B dispose(io.micronaut.context.BeanContext context, B bean) {
        if (bean instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("The " + getBeanType().getSimpleName()
                    + " of an injection point could not be closed", e);
            }
        }
        return bean;
    }

    /**
     * The bean is whatever the injection point asked for, however that injection point was qualified, so it is a
     * candidate for every qualifier rather than for one of them.
     *
     * <p>Micronaut has a qualifier that says exactly that, and a candidate declaring it matches whatever is asked
     * for. The qualifiers of the injection point are then read off the injection point itself, which is where
     * they belong: a qualified event is fired with the qualifiers it was injected with.</p>
     */
    @Override
    public final io.micronaut.context.Qualifier<B> getDeclaredQualifier() {
        return io.micronaut.inject.qualifiers.Qualifiers.any();
    }

    /**
     * Builds the bean for an injection point of the given type argument and qualifiers.
     *
     * @param resolutionContext The resolution context
     * @param context           The bean context
     * @param type              The type the injection point asked for, or {@code Object} when it asked for none
     * @param qualifiers        The qualifiers of the injection point
     * @return The bean
     */
    @Nullable
    protected abstract B build(BeanResolutionContext resolutionContext,
                               io.micronaut.context.BeanContext context,
                               Argument<?> type,
                               Set<Annotation> qualifiers);

    @Override
    @SuppressWarnings("NullAway")
    public final B instantiate(BeanResolutionContext resolutionContext, io.micronaut.context.BeanContext context) {
        Argument<?> type = Argument.OBJECT_ARGUMENT;
        AnnotationMetadata metadata = AnnotationMetadata.EMPTY_METADATA;
        BeanResolutionContext.Segment<?, ?> segment = resolutionContext.getPath().currentSegment().orElse(null);
        if (segment != null) {
            InjectionPoint<?> injectionPoint = segment.getInjectionPoint();
            if (injectionPoint instanceof ArgumentCoercible<?> coercible) {
                Argument<?> argument = coercible.asArgument();
                type = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            }
            metadata = injectionPoint.getAnnotationMetadata();
        }
        return build(resolutionContext, context, type, CdiQualifiers.declared(metadata));
    }

    @Override
    public final AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    @Override
    public final boolean isContainerType() {
        return false;
    }

    @Override
    public final boolean isConfigurationProperties() {
        // a bean definition and a reference to one both answer this, and a class that is both has to say which
        return false;
    }

    @Override
    public final boolean isEnabled(io.micronaut.context.BeanContext context,
                                   @Nullable BeanResolutionContext resolutionContext) {
        return true;
    }

    @Override
    public final boolean isAbstract() {
        // the bean type is an interface of the specification, which a bean definition would otherwise be read as
        // abstract for and left out of the candidates
        return false;
    }

    @Override
    public final boolean isSingleton() {
        // the bean is the one the injection point asked for, so there is one per injection point
        return false;
    }

    @Override
    public final String getBeanDefinitionName() {
        return getClass().getName();
    }

    @Override
    public final BeanDefinition<B> load() {
        return this;
    }

    @Override
    public final boolean isPresent() {
        return true;
    }

    @Override
    public final List<Argument<?>> getTypeArguments(Class<?> type) {
        return type == getBeanType() ? getTypeArguments() : Collections.emptyList();
    }

    @Override
    public final List<Argument<?>> getTypeArguments() {
        // the event and the lookup are parameterized by what the injection point asked for; the injection
        // point metadata is not parameterized at all
        return getBeanType().getTypeParameters().length == 0
            ? Collections.emptyList()
            : Collections.singletonList(TYPE_VARIABLE);
    }

    @Override
    public final boolean equals(Object o) {
        return o != null && getClass() == o.getClass();
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }
}
