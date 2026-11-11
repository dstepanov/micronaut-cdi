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

import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.QualifiedBeanType;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The qualifier an injection point the container resolves is selected by through
 * {@code BeanContext.findBeanDefinition}, so that the bean context keeps what the point resolved to among its
 * concrete candidates, and forgets it itself as definitions are registered or reloaded.
 *
 * <p>Handed the candidates of the class of the point, it makes the container's whole selection
 * ({@link CdiBeanContainer#select}): the rules of the specification, the narrowing of section 2.4.2 and its
 * ambiguity - an {@code AmbiguousResolutionException} thrown from here, which nothing caches - and the decision to
 * leave the point to Micronaut's rules. It then admits one candidate or none, so the precedence Micronaut applies
 * among several candidates never has its say. One of the two kinds admits the selected bean; the other admits a
 * candidate, standing for the point being left to Micronaut's rules, so that answer is kept too.</p>
 *
 * <p>Two of these are equal where the required type and qualifiers match and the qualifiers a portable extension
 * added are the same, so a qualifier added as the container starts selects again. It is only ever used to select:
 * the bean is created through the selected definition, and no scope keys an instance by this qualifier.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class SelectionQualifier<T> implements Qualifier<T> {

    private final CdiBeanContainer container;
    private final CdiBeanQualifier<Object> matching;
    private final Object overlay;
    private final boolean leftToMicronaut;

    /**
     * @param container       The container
     * @param matching        The qualifier of the point
     * @param overlay         What portable extensions added to the qualifiers, as it is now
     * @param leftToMicronaut Whether this admits a candidate where the point is left to Micronaut's rules, rather
     *                        than the selected bean
     */
    SelectionQualifier(CdiBeanContainer container, CdiBeanQualifier<Object> matching, Object overlay,
                       boolean leftToMicronaut) {
        this.container = container;
        this.matching = matching;
        this.overlay = overlay;
        this.leftToMicronaut = leftToMicronaut;
    }

    @Override
    public <B extends BeanType<T>> Collection<B> filter(Class<T> beanType, Collection<B> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        CdiBeanContainer.Selection selection = container.select(matching);
        if (leftToMicronaut) {
            return selection == CdiBeanContainer.Selection.Unselected.MICRONAUT
                ? List.of(candidates.iterator().next()) : List.of();
        }
        if (selection instanceof CdiBeanContainer.Selection.Selected selected) {
            for (B candidate : candidates) {
                if (candidate.equals(selected.definition())) {
                    return List.of(candidate);
                }
            }
        }
        return List.of();
    }

    @Override
    public <B extends QualifiedBeanType<T>> Collection<B> filterQualified(Class<T> beanType, Collection<B> candidates) {
        return filter(beanType, candidates);
    }

    @Override
    public <B extends BeanType<T>> Stream<B> reduce(Class<T> beanType, Stream<B> candidates) {
        return filter(beanType, candidates.toList()).stream();
    }

    @Override
    public boolean doesQualify(Class<T> beanType, BeanType<T> candidate) {
        return !filter(beanType, List.of(candidate)).isEmpty();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof SelectionQualifier<?> other && leftToMicronaut == other.leftToMicronaut
            && overlay == other.overlay && matching.equals(other.matching);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matching, System.identityHashCode(overlay), leftToMicronaut);
    }

    @Override
    public String toString() {
        return "Selection[" + matching + (leftToMicronaut ? ", left to Micronaut]" : "]");
    }
}
