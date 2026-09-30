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
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.List;

/**
 * The injection point metadata of the specification, with the required qualifiers as Micronaut holds them.
 *
 * <p>{@link InjectionPoint#getQualifiers()} reports a qualifier as an annotation instance, which the container
 * has to make where a program did not hand it one. This reports the same qualifiers as the values they were
 * written or selected with - every member, the ones marked {@code Nonbinding} among them - and makes nothing.
 * A qualifier a lookup selected as an {@code AnnotationValue} is reported here, and is among the instances of
 * {@code getQualifiers()} only where its annotation class is known from what was compiled.
 * The {@code InjectionPoint} a bean of this container is injected with is one of these.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface MicronautInjectionPoint extends InjectionPoint {

    /**
     * The required qualifiers of the injection point, each as the values of its members: the ones written on
     * the injection point, and for a bean obtained by a programmatic lookup the ones every {@code select} of
     * the lookup added (section 2.4.5.7). An injection point that requires none requires {@code Default}.
     *
     * <p>A qualifier a lookup was handed as an annotation instance is reported with its members where they were
     * read: the specification's own, and any other where the module that reads an annotation instance,
     * {@code micronaut-cdi-reflection}, is there. The instance itself is among {@link #getQualifiers()} as it
     * was handed over.</p>
     *
     * @return The qualifiers, in the order they were required
     */
    List<AnnotationValue<?>> getQualifierValues();
}
