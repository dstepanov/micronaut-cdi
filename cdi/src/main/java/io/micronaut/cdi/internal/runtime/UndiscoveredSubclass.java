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

import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;

import java.util.Set;

/**
 * The condition of a class that declares no bean defining annotation and only inherits a scope from its superclass.
 *
 * <p>A bean archive discovers a class by a bean defining annotation the class itself declares (section 2.5.1), so
 * such a class is no bean where it is discovered. Where a class is added to the deployment explicitly - a class the
 * SE bootstrap of a synthetic archive was handed - it is a bean, and the scope it inherits is its scope. The SE
 * bootstrap names those classes in the property this condition consults.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class UndiscoveredSubclass implements Condition {

    /**
     * The property naming the classes the SE bootstrap added to its synthetic archive, comma separated.
     */
    public static final String ADDED_CLASSES = "io.micronaut.cdi.synthetic-archive.classes";

    @Override
    public boolean matches(ConditionContext context) {
        Object component = context.getComponent();
        if (component instanceof BeanDefinition<?> definition
            && definition.getAnnotationMetadata().hasAnnotation(CdiProducer.class)) {
            // what a producer makes of the class is a bean of the producer's
            return true;
        }
        if (!(component instanceof BeanDefinition<?> definition)
            || !(context.getBeanContext() instanceof ApplicationContext application)) {
            return false;
        }
        String names = application.getProperty(ADDED_CLASSES, String.class).orElse("");
        if (names.isEmpty()) {
            return false;
        }
        Class<?> type = definition instanceof ProxyBeanDefinition<?> proxy
            ? proxy.getTargetType() : definition.getBeanType();
        return Set.of(names.split(",")).contains(type.getName());
    }
}
