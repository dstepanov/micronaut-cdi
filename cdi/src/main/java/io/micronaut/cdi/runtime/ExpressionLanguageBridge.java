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
package io.micronaut.cdi.runtime;

import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;

/**
 * What the bean manager answers the two expression-language questions with, when there is an answer.
 *
 * <p>Evaluating an expression is the business of the Jakarta Expression Language specification, and this module
 * does not carry an implementation of it. The optional {@code micronaut-cdi-el} module does, and contributes a
 * bean of this type; a bean manager that finds one delegates {@link jakarta.enterprise.inject.spi.BeanManager
 * BeanManager}'s {@code getELResolver} and {@code wrapExpressionFactory} to it, and one that finds none keeps
 * refusing them the way it refuses the rest of what it does not implement.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface ExpressionLanguageBridge {

    /**
     * The resolver that resolves a name to a bean of the container.
     *
     * @return The resolver
     */
    ELResolver resolver();

    /**
     * Wraps an expression factory so that what it evaluates sees the beans of the container.
     *
     * @param factory The factory to wrap
     * @return The wrapped factory
     */
    ExpressionFactory wrap(ExpressionFactory factory);
}
