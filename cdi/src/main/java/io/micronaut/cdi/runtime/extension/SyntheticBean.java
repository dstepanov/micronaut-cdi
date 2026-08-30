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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/**
 * What an extension said about a bean it wants the container to have, gathered from the builder it described it
 * with.
 *
 * @param implementationClass The class the bean is created as
 * @param types               The types the bean is resolvable by
 * @param qualifiers          The qualifiers it carries
 * @param scope               The scope it belongs to, or {@code null} for the dependent pseudo-scope
 * @param name                The name it was given, if it was given one
 * @param priority            The priority it was given, if it was given one
 * @param alternative         Whether it is an alternative
 * @param stereotypes         The stereotypes the extension put on it
 * @param parameters          What the extension attached to it for its creator to read
 * @param creator             The class that creates it
 * @param disposer            The class that disposes of it, if there is one
 * @param <T>                 The type of the bean
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public record SyntheticBean<T>(
    Class<T> implementationClass,
    List<Class<?>> types,
    List<Annotation> qualifiers,
    @Nullable Class<? extends Annotation> scope,
    @Nullable String name,
    @Nullable Integer priority,
    boolean alternative,
    List<Class<? extends Annotation>> stereotypes,
    Map<String, Object> parameters,
    Class<? extends SyntheticBeanCreator<T>> creator,
    @Nullable Class<? extends SyntheticBeanDisposer<T>> disposer
) {
}
