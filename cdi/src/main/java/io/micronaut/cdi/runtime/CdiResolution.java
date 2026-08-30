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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The one rule of section 2.4.2 that picks a bean from several candidates: the alternatives outrank everything
 * else, and among the alternatives the highest priority wins. Anything short of a single winner is an ambiguity.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiResolution {

    private CdiResolution() {
    }

    /**
     * Narrows the candidates the way resolution does. The result has one element where resolution succeeds, and
     * more where it is ambiguous.
     *
     * @param candidates The candidate definitions
     * @param <T>        The bean type
     * @return The narrowed candidates
     */
    public static <T extends BeanDefinition<?>> List<T> narrow(Collection<T> candidates) {
        if (candidates.size() <= 1) {
            return List.copyOf(candidates);
        }
        List<T> alternatives = new ArrayList<>();
        for (T candidate : candidates) {
            if (candidate.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Alternative")
                || candidate.getAnnotationMetadata().hasStereotype("jakarta.enterprise.inject.Alternative")) {
                alternatives.add(candidate);
            }
        }
        if (alternatives.isEmpty()) {
            return List.copyOf(candidates);
        }
        List<T> best = new ArrayList<>();
        int bestPriority = Integer.MIN_VALUE;
        for (T alternative : alternatives) {
            // the priority a selected alternative was written with is carried as its order, negated: an order
            // prefers the lowest where a priority prefers the highest
            int priority = priorityOf(alternative);
            if (priority > bestPriority) {
                bestPriority = priority;
                best.clear();
            }
            if (priority == bestPriority) {
                best.add(alternative);
            }
        }
        return List.copyOf(best);
    }

    /**
     * The priority a bean was selected with, read back from the order it was recorded as.
     *
     * @param definition The definition
     * @return The priority
     */
    /**
     * Whether the bean of the definition is in the dependent pseudo-scope, which is what makes an instance of
     * it belong to whoever asked for it.
     *
     * @param definition The definition
     * @return Whether it is dependent
     */
    public static boolean isDependent(BeanDefinition<?> definition) {
        if (definition.isSingleton()) {
            return false;
        }
        // the scope the bean was described with, where one was recorded — a synthetic bean's metadata names
        // it without carrying the compiled stereotypes
        String declared = definition.getAnnotationMetadata()
            .stringValue("io.micronaut.cdi.annotation.CdiScope").orElse(null);
        if (declared != null) {
            return "jakarta.enterprise.context.Dependent".equals(declared);
        }
        return !definition.getAnnotationMetadata()
            .hasStereotype("io.micronaut.cdi.annotation.CdiApplicationScope")
            && !definition.getAnnotationMetadata()
            .hasStereotype("io.micronaut.cdi.annotation.CdiRequestScope");
    }

    public static int priorityOf(BeanDefinition<?> definition) {
        int order = definition.getOrder();
        return order == 0 ? Integer.MIN_VALUE : -order;
    }
}
