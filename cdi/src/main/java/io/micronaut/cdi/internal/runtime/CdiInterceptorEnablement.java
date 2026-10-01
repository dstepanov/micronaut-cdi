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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.interceptor.runtime.BoundInterceptorEnablement;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Has the interception that runs follow the enablement of the specification: an interceptor bound by an interceptor
 * binding takes part only where it is enabled. Section 2.7 has CDI Lite enable and order interceptors by
 * {@code @Priority}, which is how section 5.2.1 of Jakarta Interceptors enables an interceptor bound by an
 * interceptor binding; section 5.1 of Jakarta Interceptors enables one named with {@code @Interceptors} by that
 * annotation.
 *
 * <p>The Jakarta Interceptors implementation resolves the chains, and on its own enables every interceptor class.
 * It asks this bean about each one a binding binds, and the bean manager asks it as well, through
 * {@link CdiInterceptor}, so that the interceptors {@code resolveInterceptors} reports are the interceptors that are
 * invoked. An interceptor class a bean names with {@code @Interceptors} is not asked about: it is enabled by being
 * named.</p>
 *
 * <p>An interceptor is enabled by the priority it declares, {@code @Priority}, or by the SE bootstrap, whose
 * {@code SeContainerInitializer.enableInterceptors} section 4.1 has add interceptor classes to the enabled
 * interceptors of the synthetic bean archive. Micronaut's own {@code @Order} is taken for a priority as well, which
 * is an extension of this implementation and of neither specification: the interceptors implementation orders a
 * chain by it where no {@code @Priority} is declared.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class CdiInterceptorEnablement implements BoundInterceptorEnablement {

    /**
     * The property naming the interceptor classes the SE bootstrap enabled, comma separated.
     */
    public static final String ENABLED_CLASSES = "io.micronaut.cdi.interceptors.classes";

    private static final String ORDER = "io.micronaut.core.annotation.Order";
    private static final String PRIORITY = "jakarta.annotation.Priority";

    // in the order the bootstrap was given them
    private final List<String> enabledClasses;

    /**
     * @param beanContext The bean context, whose properties name what the bootstrap enabled
     */
    public CdiInterceptorEnablement(BeanContext beanContext) {
        String names = beanContext instanceof PropertyResolver properties
            ? properties.getProperty(ENABLED_CLASSES, String.class).orElse("")
            : "";
        this.enabledClasses = names.isEmpty() ? List.of() : List.of(names.split(","));
    }

    @Override
    public boolean isEnabled(BeanDefinition<?> interceptor) {
        AnnotationMetadata metadata = interceptor.getAnnotationMetadata();
        return metadata.hasAnnotation(ORDER)
            || metadata.hasAnnotation(PRIORITY)
            || enabledClasses.contains(interceptor.getBeanType().getName());
    }

    /**
     * The place of an interceptor among the ones the SE bootstrap enabled, which is the order they were given in.
     * Section 4.1 has the bootstrap add them to the enabled interceptors of the synthetic bean archive, which
     * behaves like an explicit bean archive; the interceptors enabled for a bean archive are ordered as they are
     * listed and called after the ones enabled by a priority (section 3.6.2), and one that declares a priority is
     * ordered by it whether or not it is listed as well.
     */
    @Override
    public int position(BeanDefinition<?> interceptor) {
        AnnotationMetadata metadata = interceptor.getAnnotationMetadata();
        if (metadata.hasAnnotation(ORDER) || metadata.hasAnnotation(PRIORITY)) {
            return -1;
        }
        return enabledClasses.indexOf(interceptor.getBeanType().getName());
    }
}
