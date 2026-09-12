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
package io.quarkus.arc;

import jakarta.enterprise.inject.spi.Bean;
import org.jspecify.annotations.Nullable;

/**
 * ArC's name for a {@link Bean}, with the three things ArC's tests ask a bean that the specification's interface
 * does not answer.
 *
 * <p>The priority and the declaring bean are both in the specification — an alternative's priority is its
 * {@code @Priority} (section 2.7.1), and a producer's bean is the bean that declares it (section 3.3) — they are
 * simply not on {@code Bean}, so ArC put them on its own interface. The identifier is ArC's alone: it is the name
 * of the class ArC generated for the bean, and what stands in for it here is the name of the definition this
 * implementation compiled, which is as stable and as unique.</p>
 *
 * @param <T> The bean type
 */
public interface InjectableBean<T> extends Bean<T> {

    /**
     * @return An identifier unique to this bean within the container
     */
    String getIdentifier();

    /**
     * @return Whether this bean declares a priority of its own
     */
    boolean hasPriority();

    /**
     * @return The priority of this bean as an alternative, or 0 where it declares none
     */
    int getPriority();

    /**
     * @return The bean that declares this one, where it is a producer's bean, and otherwise null
     */
    @Nullable
    InjectableBean<?> getDeclaringBean();
}
