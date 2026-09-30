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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The qualifiers a portable extension added to a bean class as the container started, which the compiled
 * definition of the bean does not carry: what a bean is qualified by is what its definition says, and this
 * beside it.
 *
 * <p>It is read where this container compares qualifiers itself - a programmatic lookup, the bean container, the
 * qualifiers a bean reports. An injection point is resolved by Micronaut from the compiled metadata of the
 * definitions, which this does not reach.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class QualifierOverlay {

    private final Map<String, List<CdiQualifier>> added = new ConcurrentHashMap<>();

    /**
     * Adds a qualifier to the beans of a class.
     *
     * @param beanClass The name of the bean class
     * @param qualifier The qualifier
     */
    public void add(String beanClass, CdiQualifier qualifier) {
        added.compute(beanClass, (name, qualifiers) -> {
            List<CdiQualifier> all = qualifiers == null ? new ArrayList<>() : new ArrayList<>(qualifiers);
            all.add(qualifier);
            return List.copyOf(all);
        });
    }

    /**
     * Whether nothing was added, which is the case of every container no portable extension ran in.
     *
     * @return Whether the overlay is empty
     */
    public boolean isEmpty() {
        return added.isEmpty();
    }

    /**
     * The qualifiers of a bean with what was added to its class: a qualifier other than {@code Any} and
     * {@code Named} takes {@code Default} away, as it does where it is written (section 2.2.8).
     *
     * @param definition The definition of the bean
     * @param declared   The qualifiers it was compiled with
     * @return The qualifiers
     */
    List<CdiQualifier> apply(BeanDefinition<?> definition, List<CdiQualifier> declared) {
        if (added.isEmpty() || definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")) {
            // what a producer of the class produces is a bean of its own, which the class's annotations do
            // not qualify
            return declared;
        }
        Class<?> type = definition instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType()
            : definition.getBeanType();
        List<CdiQualifier> extra = added.get(type.getName());
        if (extra == null) {
            return declared;
        }
        List<CdiQualifier> all = new ArrayList<>(declared.size() + extra.size());
        boolean named = false;
        for (CdiQualifier qualifier : extra) {
            named |= !qualifier.isAny() && !qualifier.isNamed();
        }
        for (CdiQualifier qualifier : declared) {
            if (!named || !qualifier.isDefault()) {
                all.add(qualifier);
            }
        }
        for (CdiQualifier qualifier : extra) {
            if (!qualifier.isAmong(all)) {
                all.add(qualifier);
            }
        }
        return List.copyOf(all);
    }
}
