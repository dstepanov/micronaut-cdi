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
package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.metadata.CdiBuiltIn;
import io.micronaut.cdi.internal.metadata.CdiExtensionComponents;
import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.cdi.internal.metadata.CdiRegisteredContext;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.metadata.CdiSyntheticDisposer;
import io.micronaut.cdi.internal.metadata.CdiSyntheticObserver;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.ProxyBeanDefinition;
import org.jspecify.annotations.Nullable;

/**
 * Tells the bean definitions generated for the classes an extension named — a creator, a disposer, a synthetic
 * observer, a context — from the beans of the application.
 *
 * <p>The container instantiates those classes through a definition so that it need not instantiate them
 * reflectively. The specification does not make them beans, and they are not reported as any: they are
 * infrastructure of the container that happens to be compiled the way a bean is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ExtensionComponents {

    private ExtensionComponents() {
    }

    /**
     * Whether the definition with the given metadata was generated for a class an extension named.
     *
     * @param metadata The metadata of a bean definition
     * @return Whether it is one of the container's own
     */
    public static boolean isComponent(AnnotationMetadata metadata) {
        return metadata.hasAnnotation(CdiExtensionComponents.class)
            || metadata.hasAnnotation(CdiSyntheticBean.class)
            || metadata.hasAnnotation(CdiSyntheticDisposer.class)
            || metadata.hasAnnotation(CdiSyntheticObserver.class)
            || metadata.hasAnnotation(CdiRegisteredContext.class);
    }

    /**
     * Whether a bean is infrastructure every container has rather than a bean of the application: the one rule the
     * SE bootstrap selects the beans of an archive by and the portable extensions are told about the beans by.
     *
     * <p>A built-in bean of the container is, and so is a component an extension had the container instantiate. A
     * bean of the specification - one the processor recorded a scope or a producer of - is not: it is a bean of the
     * application whatever its package, even under {@code io.micronaut}. A bean with nothing of the specification
     * recorded - a plain {@code jakarta.inject} singleton of the application as much as one of Micronaut's own - is
     * infrastructure only where its class is the framework's, under {@code io.micronaut} or {@code jakarta}: missing
     * metadata alone does not make a bean the framework's. A bean such a class produces as a factory is the
     * framework's too.</p>
     *
     * @param bean The bean
     * @return Whether it is infrastructure
     */
    public static boolean isInfrastructure(BeanType<?> bean) {
        AnnotationMetadata metadata = bean.getAnnotationMetadata();
        if (metadata.hasAnnotation(CdiBuiltIn.class) || isComponent(metadata)) {
            // what a build compatible extension synthesised is recorded on the definition of a class it named, and
            // is the container's whichever archive that class is in
            return true;
        }
        if (metadata.hasAnnotation(CdiScope.class) || producerOf(bean) != null) {
            return false;
        }
        // a bean Micronaut produces from a factory of its own is the framework's whatever type it produces
        Class<?> declaring = bean instanceof BeanDefinition<?> definition && !(bean instanceof ProxyBeanDefinition<?>)
            ? definition.getDeclaringType().orElse(null) : null;
        return isFrameworkClass(classOf(bean)) || declaring != null && isFrameworkClass(declaring);
    }

    private static boolean isFrameworkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("io.micronaut.") || name.startsWith("jakarta.");
    }

    /**
     * The class of a bean, the class a client proxy stands in front of for a proxy.
     *
     * @param bean The bean
     * @return The class
     */
    public static Class<?> classOf(BeanType<?> bean) {
        return bean instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType() : bean.getBeanType();
    }

    /**
     * The class that declares the producer of a produced bean, as the processor recorded it, or {@code null} for
     * a bean that is a class itself.
     *
     * @param bean The bean
     * @return The name of the declaring class, or {@code null}
     */
    public static @Nullable String producerOf(BeanType<?> bean) {
        if (!(bean instanceof BeanDefinition<?> definition)) {
            return null;
        }
        return definition.getAnnotationMetadata()
            .stringValue(CdiProducer.class, "declaringType").orElse(null);
    }
}
