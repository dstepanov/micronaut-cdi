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
package io.micronaut.cdi;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;

/**
 * The event of the specification, with the selections Micronaut can make without reading anything back.
 *
 * <p>{@link Event} selects by annotation instances and by {@link TypeLiteral}, which are reflection objects.
 * A selection made with an {@link AnnotationValue} and an {@link Argument} is resolved from what was compiled,
 * with no reflection module on the classpath. Selecting by {@code Argument} also states the event type in
 * full: an event fired through the result is an event of exactly that type, whatever its class says of its
 * type variables, which is how an event of a generic class is fired without its class being read.</p>
 *
 * <p>Every {@code Event} this container hands out is one of these, whether it was injected or obtained from
 * {@code BeanContainer.getEvent()}, and an injection point may be declared with this type directly.</p>
 *
 * @param <T> The event type
 * @author Denis Stepanov
 * @since 1.0
 */
public interface MicronautEvent<T> extends Event<T> {

    /**
     * Obtains a child event for the given additional qualifiers, each given as the values it is written with.
     * The rules of {@link Event#select(Annotation...)} apply: each has to be a qualifier retained at runtime,
     * and a qualifier type that is not repeatable is given once.
     *
     * @param qualifier  A qualifier
     * @param qualifiers Further qualifiers
     * @return The child event
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    MicronautEvent<T> select(AnnotationValue<?> qualifier, AnnotationValue<?>... qualifiers);

    /**
     * Obtains a child event for the given event type and additional qualifiers, each given as the values it is
     * written with.
     *
     * @param subtype    The event type
     * @param qualifier  A qualifier
     * @param qualifiers Further qualifiers
     * @param <U>        The event type
     * @return The child event
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    <U extends T> MicronautEvent<U> select(Class<U> subtype, AnnotationValue<?> qualifier,
                                           AnnotationValue<?>... qualifiers);

    /**
     * Obtains a child event for the given event type, which may be parameterized, and additional qualifiers.
     * This is the counterpart of {@link Event#select(TypeLiteral, Annotation...)} that needs no reflection,
     * and the type given is the type of the events fired through the result.
     *
     * @param subtype    The event type
     * @param qualifiers The additional qualifiers
     * @param <U>        The event type
     * @return The child event
     * @throws IllegalArgumentException Where the type names a type variable, or an annotation is not a
     *                                  qualifier or is given twice
     */
    <U extends T> MicronautEvent<U> select(Argument<U> subtype, AnnotationValue<?>... qualifiers);

    @Override
    MicronautEvent<T> select(Annotation... qualifiers);

    @Override
    <U extends T> MicronautEvent<U> select(Class<U> subtype, Annotation... qualifiers);

    @Override
    <U extends T> MicronautEvent<U> select(TypeLiteral<U> subtype, Annotation... qualifiers);
}
