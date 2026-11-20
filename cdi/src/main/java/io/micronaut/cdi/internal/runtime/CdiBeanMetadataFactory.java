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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;

import java.util.Iterator;
import java.util.List;

/**
 * Builds the {@code Bean} metadata of section 5.5.8 of CDI Lite: the {@code @Default Bean} injected into a bean is
 * that bean's own metadata.
 *
 * <p>Like the injection point metadata ({@link CdiCurrentInjectionPointFactory}), the answer is on the resolution
 * path: the metadata is being injected into the bean being created, which declares the first segment - the
 * injection of the metadata itself. The factory method of a produced bean is that bean's constructor to Micronaut,
 * so a parameter of a producer method names the produced bean. A lookup a synthetic bean's creation makes through
 * its {@code SyntheticInjections} is a {@link LookupSegment} whose injection point belongs to the synthetic bean.
 * The bean is the container's own {@link CdiBean} of that definition, the one {@code BeanContainer.getBeans}
 * answers with, and the {@link CdiInterceptor} of an interceptor class.</p>
 *
 * <p>The {@code @Intercepted Bean} of an interceptor is not built: an interceptor instance is created by the
 * Jakarta Interceptors advice through a dependency resolver of its own, whose resolution starts a path of its own
 * that does not name the bean intercepted, and nothing else Micronaut Core hands the creation does either.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiBeanMetadataFactory extends AbstractCdiInjectionPointBeanDefinition<Bean<?>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Bean<?>> getBeanType() {
        return (Class) Bean.class;
    }

    @Override
    protected Bean<?> build(BeanResolutionContext resolutionContext,
                            BeanContext context,
                            Argument<?> type,
                            List<CdiQualifier> qualifiers) {
        for (CdiQualifier qualifier : qualifiers) {
            if (Intercepted.class.getName().equals(qualifier.name())) {
                throw new UnsupportedOperationException("The @Intercepted Bean of an interceptor is not implemented: "
                    + "the creation of an interceptor instance does not name the bean it intercepts (see "
                    + "CONFORMANCE.md, Not yet implemented)");
            }
        }
        return declaringBean(context.getBean(CdiBeanContainer.class), resolutionContext);
    }

    /**
     * Whether a parameter asks for the {@code @Default Bean} metadata, which the container hands a parameter that is
     * no injection point of a bean being created itself - a parameter of an observer method, handed the metadata of
     * the bean declaring it, and one of a disposer method, handed the metadata of the bean it disposes of - since the
     * resolution of such a parameter starts a path of its own that names neither.
     *
     * @param argument The parameter
     * @return Whether it is the {@code @Default Bean}
     */
    static boolean isDefaultMetadata(Argument<?> argument) {
        if (argument.getType() != Bean.class) {
            return false;
        }
        for (CdiQualifier qualifier : CdiQualifier.declared(argument.getAnnotationMetadata())) {
            if (!qualifier.isDefault()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bean that declares the injection point being resolved: the bean being created, as the first segment of
     * the path names it, or the bean of the injection point a lookup was made at.
     *
     * @param container         The container
     * @param resolutionContext The resolution under way
     * @return The bean, an {@link jakarta.enterprise.inject.spi.Interceptor} where it is an interceptor class
     */
    static Bean<?> declaringBean(CdiBeanContainer container, BeanResolutionContext resolutionContext) {
        Iterator<BeanResolutionContext.Segment<?, ?>> segments = resolutionContext.getPath().iterator();
        if (segments.hasNext()) {
            BeanResolutionContext.Segment<?, ?> self = segments.next();
            if (self.getDeclaringType() instanceof AbstractCdiInjectionPointBeanDefinition<?>) {
                // the creation of the metadata itself heads a resolution of its own - a parameter of an observer or a
                // disposer, which the container hands the metadata itself (isDefaultMetadata), or a plain lookup of
                // the metadata - which names no bean it is injected into
                throw new IllegalStateException("Bean metadata is the metadata of the bean it is injected into, and "
                    + "this resolution is injecting into no bean");
            }
            if (self instanceof LookupSegment<?, ?> lookup) {
                Bean<?> bean = lookup.selected().getBean();
                if (bean != null) {
                    return bean;
                }
            } else {
                BeanDefinition<?> definition = self.getDeclaringType();
                return CdiBeanQualifier.isInterceptor(definition)
                    ? new CdiInterceptor<>(container.beanContext(), definition, container.overlay())
                    : container.beanOf(definition);
            }
        }
        // a lookup through an Instance injected nowhere has no bean to describe
        throw new IllegalStateException("Bean metadata is the metadata of the bean it is injected into, and "
            + "this resolution is injecting into no bean");
    }
}
