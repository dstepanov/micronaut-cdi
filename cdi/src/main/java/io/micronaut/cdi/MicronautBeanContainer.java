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
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.util.Set;

/**
 * The bean container of the specification, with the lookups Micronaut can make without reading anything back:
 * by {@link Argument} in place of a {@code java.lang.reflect.Type}, and by {@link AnnotationValue} in place of
 * an annotation instance.
 *
 * <p>The container this module provides is one of these: what {@code CDI.current().getBeanContainer()}
 * answers, and what is injected for {@code BeanContainer}, may be used as this type.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface MicronautBeanContainer extends BeanContainer {

    /**
     * The beans that have the given required type and qualifiers, each qualifier given as the values it is
     * written with. The rules of {@link BeanContainer#getBeans(java.lang.reflect.Type,
     * java.lang.annotation.Annotation...)} apply.
     *
     * <p>An {@link Argument} is a {@code java.lang.reflect.Type}, so the specification's own
     * {@code getBeans(Type, Annotation...)} takes one as it is where no qualifier, or a literal, is given.</p>
     *
     * @param beanType   The required type
     * @param qualifier  A required qualifier
     * @param qualifiers Further required qualifiers
     * @return The beans that qualify
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    Set<Bean<?>> getBeans(Argument<?> beanType, AnnotationValue<?> qualifier, AnnotationValue<?>... qualifiers);

    /**
     * The observer methods an event fired with the given qualifiers notifies, each qualifier given as the
     * values it is written with.
     *
     * @param event      The event
     * @param qualifier  A qualifier the event is fired with
     * @param qualifiers Further qualifiers
     * @param <T>        The event type
     * @return The observer methods
     * @throws IllegalArgumentException Where an annotation is not a qualifier, or is given twice
     */
    <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, AnnotationValue<?> qualifier,
                                                              AnnotationValue<?>... qualifiers);

    @Override
    MicronautEvent<Object> getEvent();

    @Override
    MicronautInstance<Object> createInstance();
}
