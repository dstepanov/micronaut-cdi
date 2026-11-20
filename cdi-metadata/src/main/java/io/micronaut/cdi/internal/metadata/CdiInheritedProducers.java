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
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records, on a class that extends a class that declares producers, which producers of its superclasses Micronaut
 * would produce again under it: Micronaut builds the products of every producer field and producer method of a
 * factory, those it inherits included, while a producer is no member a subclass inherits (CDI 4.1 section
 * 4.2). A condition of the class takes the products of those producers out of it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Internal
public @interface CdiInheritedProducers {

    /**
     * The inherited producers, each as the binary name of the class declaring it, {@code #} and its name.
     *
     * @return The producers
     */
    String[] value();
}
