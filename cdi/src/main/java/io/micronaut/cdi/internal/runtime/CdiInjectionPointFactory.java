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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentCoercible;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.provider.AbstractInjectionPointBeanDefinition;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The base of the beans of this module that are what the injection point asked for rather than a bean of a
 * type of their own: the event a program fires, the lookup it resolves beans through, and the injection point
 * metadata itself.
 *
 * <p>The event and the lookup are parameterized by the type at the injection point and qualified by its
 * qualifiers, which means neither can be a bean definition Micronaut generates from a class: there is no one
 * {@code Event<T>} to generate, and the bean has to be built when it is injected rather than before. Micronaut
 * writes such a bean by hand as an injection point bean definition — its own reference, a candidate for any
 * qualifier, bound to the type argument of the injection point — and this is that, with the reading of the
 * injection point done once for all of them: a qualified event is fired with the qualifiers it was injected
 * with, which are read off the injection point rather than matched against the definition.</p>
 *
 * <p>The bean is a dependent of whoever it was injected into, so it is disposed of with it, and closing it
 * closes what it made.</p>
 *
 * @param <B> The type of the bean produced
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public abstract class CdiInjectionPointFactory<B> extends AbstractInjectionPointBeanDefinition.Disposable<B> {

    protected CdiInjectionPointFactory() {
    }

    /**
     * @param mayBuildNothing Whether the bean may legitimately be null — the injection point metadata of an
     *                        object that is not being injected anywhere is null, and the specification says to
     *                        inject it that way
     */
    protected CdiInjectionPointFactory(boolean mayBuildNothing) {
        super(mayBuildNothing ? nullable() : AnnotationMetadata.EMPTY_METADATA);
    }

    private static AnnotationMetadata nullable() {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        metadata.addDeclaredAnnotation(AnnotationUtil.NULLABLE, Map.of());
        return metadata;
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
                               BeanContext context,
                               Argument<?> type,
                               List<CdiQualifier> qualifiers);

    /**
     * Whether the built-in bean is resolvable by the given raw type: the type the specification gives it, a
     * type that one extends, or the Micronaut type it is built as.
     *
     * @param raw The raw required type
     * @return Whether it is a type of the bean
     */
    final boolean isBeanType(java.lang.reflect.Type raw) {
        return raw.equals(getBeanType()) || getExposedTypes().contains(raw);
    }

    @Override
    // a built-in bean may have nothing to hand out - the InjectionPoint of a bean that was not injected - and
    // says so with null, which Micronaut resolves as a bean that is not there; the definition's signature has no
    // way to say it
    @SuppressWarnings("NullAway")
    protected final B build(BeanResolutionContext resolutionContext,
                            BeanContext context,
                            @Nullable InjectionPoint<?> injectionPoint) {
        Argument<?> type = Argument.OBJECT_ARGUMENT;
        AnnotationMetadata metadata = AnnotationMetadata.EMPTY_METADATA;
        if (injectionPoint != null) {
            if (injectionPoint instanceof ArgumentCoercible<?> coercible) {
                Argument<?> argument = coercible.asArgument();
                type = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            }
            metadata = injectionPoint.getAnnotationMetadata();
        }
        return build(resolutionContext, context, type, CdiQualifier.declared(metadata));
    }
}
