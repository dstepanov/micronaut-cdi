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
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanType;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The qualifiers of the specification as the one Micronaut qualifier a lookup of this container resolves by: a
 * bean qualifies when it has every qualifier the lookup requires, by the rules of section 2.4.2.
 *
 * <p>Micronaut's own annotation qualifiers are not used for that. They compare a qualifier by the members the
 * candidate's metadata records, which leaves out the qualifiers a portable extension added to a bean and the default
 * qualifier of one that declares none; and one read off an injection point, when the candidate does not carry the
 * annotation, falls back to matching the annotation's simple name against the bean's, which would have a lookup of
 * {@code @Special Greeter} find a {@code class SpecialGreeter} as well. Here a candidate qualifies by the qualifiers
 * the container says the
 * bean has - the ones it was compiled with, and the ones a portable extension added as the container started - and
 * by nothing else. A candidate that is not a bean definition has no qualifiers to compare, and does not
 * qualify.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiBeanQualifier<T> implements Qualifier<T> {

    private final List<CdiQualifier> required;
    private final Function<BeanDefinition<?>, List<CdiQualifier>> qualifiersOf;

    /**
     * @param required     The qualifiers the lookup requires, none at all requiring the default qualifier
     * @param qualifiersOf The qualifiers of the bean a definition describes
     */
    CdiBeanQualifier(List<CdiQualifier> required, Function<BeanDefinition<?>, List<CdiQualifier>> qualifiersOf) {
        this.required = List.copyOf(required);
        this.qualifiersOf = qualifiersOf;
    }

    /**
     * The qualifiers the lookup requires.
     *
     * @return The qualifiers
     */
    public List<CdiQualifier> required() {
        return required;
    }

    @Override
    public <BT extends BeanType<T>> Stream<BT> reduce(Class<T> beanType, Stream<BT> candidates) {
        return candidates.filter(candidate -> doesQualify(beanType, candidate));
    }

    @Override
    public boolean doesQualify(Class<T> beanType, BeanType<T> candidate) {
        return candidate instanceof BeanDefinition<?> definition
            && CdiAssignability.areQualifiersMatching(qualifiersOf.apply(definition), required);
    }

    /**
     * Whether this qualifier requires every qualifier the other one does, so that every bean it selects is
     * selected by the other one too.
     *
     * @param qualifier The other qualifier
     * @return Whether this one contains it
     */
    @Override
    public boolean contains(Qualifier<T> qualifier) {
        if (!(qualifier instanceof CdiBeanQualifier<T> other)) {
            return false;
        }
        for (CdiQualifier one : other.required) {
            if (!one.isAmong(required)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CdiBeanQualifier<?> other) || required.size() != other.required.size()) {
            return false;
        }
        // the order the qualifiers were named in makes no difference to the beans selected
        for (CdiQualifier one : required) {
            if (!one.isAmong(other.required)) {
                return false;
            }
        }
        for (CdiQualifier one : other.required) {
            if (!one.isAmong(required)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // over the names alone, as a qualifier's own hash is: two qualifiers that match are of one type, while
        // which members bind is only settled once both sides are compared
        Set<String> names = new HashSet<>();
        for (CdiQualifier qualifier : required) {
            names.add(qualifier.name());
        }
        return names.hashCode();
    }

    @Override
    public String toString() {
        return required.isEmpty() ? "@jakarta.enterprise.inject.Default" : required.toString();
    }
}
