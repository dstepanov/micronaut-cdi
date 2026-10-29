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

import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jspecify.annotations.Nullable;

import java.util.stream.Stream;

/**
 * The qualifier that keeps the beans of Micronaut's own: what an injection point of a bean of the specification is
 * resolved among where it is left to Micronaut's rules. A bean of the specification that the container did not
 * select for the point - one whose qualifiers the point rejected - is not admitted again by Micronaut's rules, by
 * the prefix of its name or by being primary.
 *
 * <p>There is one instance, equal to itself alone and composed with the qualifier Micronaut reads off the point
 * into a value equal to every other composition of the same two: Micronaut keeps the candidates of a lookup by its
 * type and qualifier, so a qualifier made for each lookup would be a key of its own each time.</p>
 *
 * @param <T> The bean type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class NotOfTheSpecification<T> implements Qualifier<T> {

    @SuppressWarnings("rawtypes")
    private static final NotOfTheSpecification INSTANCE = new NotOfTheSpecification<>();

    private NotOfTheSpecification() {
    }

    /**
     * The qualifier Micronaut's rules resolve the point by, among the beans of Micronaut's own alone.
     *
     * @param point     The injection point
     * @param qualifier The qualifier Micronaut handed over for it, or {@code null} to read it off the point
     * @param <T>       The bean type
     * @return The qualifier
     */
    @SuppressWarnings("unchecked")
    static <T> Qualifier<T> forPoint(Argument<T> point, @Nullable Qualifier<T> qualifier) {
        Qualifier<T> read = qualifier != null ? qualifier : Qualifiers.forArgument(point);
        Qualifier<T> plain = (Qualifier<T>) INSTANCE;
        return read == null ? plain : Qualifiers.byQualifiers(read, plain);
    }

    @Override
    public <B extends BeanType<T>> Stream<B> reduce(Class<T> beanType, Stream<B> candidates) {
        return candidates.filter(candidate -> !candidate.getAnnotationMetadata()
            .hasStereotype(CdiScope.class));
    }

    @Override
    public String toString() {
        return "NotOfTheSpecification";
    }
}
