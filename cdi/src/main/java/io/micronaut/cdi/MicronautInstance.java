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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;

/**
 * The programmatic lookup of the specification, with the selections Micronaut can make without reading
 * anything back.
 *
 * <p>{@link Instance} selects by annotation instances and by {@link TypeLiteral}, and both are reflection
 * objects: the members of an annotation instance a program hands over can only be read reflectively, which
 * this container leaves to the optional {@code micronaut-cdi-reflection} module. Micronaut's own form of an
 * annotation is an {@link AnnotationValue}, and its own form of a type is an {@link Argument}; a selection made
 * with those is resolved from what was compiled, with no reflection module on the classpath.</p>
 *
 * <p>Every {@code Instance} this container hands out is one of these, whether it was injected, obtained from
 * {@code BeanContainer.createInstance()} or from {@code CDI.current()}, and an injection point may be declared
 * with this type directly.</p>
 *
 * @param <T> The required bean type
 * @author Denis Stepanov
 * @since 1.0
 */
public interface MicronautInstance<T> extends Instance<T> {

    /**
     * Obtains a child lookup for the given additional required qualifiers, each given as the values it is
     * written with. The rules of {@link Instance#select(Annotation...)} apply: each has to be a qualifier, and
     * a qualifier type that is not repeatable is given once.
     *
     * @param qualifier  A required qualifier
     * @param qualifiers Further required qualifiers
     * @return The child lookup
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    MicronautInstance<T> select(AnnotationValue<?> qualifier, AnnotationValue<?>... qualifiers);

    /**
     * Obtains a child lookup for the given required type and additional required qualifiers, each given as
     * the values it is written with.
     *
     * @param subtype    The required type
     * @param qualifier  A required qualifier
     * @param qualifiers Further required qualifiers
     * @param <U>        The required type
     * @return The child lookup
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    <U extends T> MicronautInstance<U> select(Class<U> subtype, AnnotationValue<?> qualifier,
                                              AnnotationValue<?>... qualifiers);

    /**
     * Obtains a child lookup for the given required type, which may be parameterized, and additional required
     * qualifiers. This is the counterpart of {@link Instance#select(TypeLiteral, Annotation...)} that needs no
     * reflection.
     *
     * @param subtype    The required type
     * @param qualifiers The additional required qualifiers
     * @param <U>        The required type
     * @return The child lookup
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    <U extends T> MicronautInstance<U> select(Argument<U> subtype, AnnotationValue<?>... qualifiers);

    @Override
    MicronautInstance<T> select(Annotation... qualifiers);

    @Override
    <U extends T> MicronautInstance<U> select(Class<U> subtype, Annotation... qualifiers);

    @Override
    <U extends T> MicronautInstance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers);
}
