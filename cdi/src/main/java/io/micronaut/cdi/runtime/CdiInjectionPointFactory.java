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
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.provider.AbstractInjectionPointBeanDefinition;
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
 * and the bean has to be built when it is injected rather than before. Micronaut has a base for such a bean — a
 * bean definition that is its own reference, of any qualifier, and builds its bean from the injection point — and
 * this reads the type argument and the qualifiers of that injection point once for both.</p>
 *
 * @param <B> The type of the bean produced
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public abstract class CdiInjectionPointFactory<B> extends AbstractInjectionPointBeanDefinition<B>
    implements io.micronaut.inject.DisposableBeanDefinition<B> {

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
                throw new IllegalStateException("The " + getBeanType().getName()
                    + " of an injection point could not be closed", e);
            }
        }
        return bean;
    }

    /**
     * Builds the bean for an injection point of the given type argument and qualifiers.
     *
     * <p>The bean is a candidate for every qualifier rather than for one of them, being whatever the injection
     * point asked for however it was qualified. The qualifiers of the injection point are read off the injection
     * point itself, which is where they belong: a qualified event is fired with the qualifiers it was injected
     * with.</p>
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
    protected final B build(BeanResolutionContext resolutionContext,
                            io.micronaut.context.BeanContext context,
                            @Nullable InjectionPoint<?> injectionPoint) {
        Argument<?> type = Argument.OBJECT_ARGUMENT;
        AnnotationMetadata metadata = AnnotationMetadata.EMPTY_METADATA;
        if (injectionPoint != null) {
            if (injectionPoint instanceof ArgumentCoercible<?> coercible) {
                type = coercible.asArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
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
    public final List<Argument<?>> getTypeArguments() {
        // the event and the lookup are parameterized by what the injection point asked for; the injection
        // point metadata is not parameterized at all
        return getBeanType().getTypeParameters().length == 0
            ? Collections.emptyList()
            : super.getTypeArguments();
    }
}
