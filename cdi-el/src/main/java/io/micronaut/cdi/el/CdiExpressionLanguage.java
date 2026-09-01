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
package io.micronaut.cdi.el;

import io.micronaut.cdi.runtime.ExpressionLanguageBridge;
import io.micronaut.context.BeanContext;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Singleton;

/**
 * The bean the bean manager answers the expression-language questions through, when this module is present.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
final class CdiExpressionLanguage implements ExpressionLanguageBridge {

    private final BeanContainer beans;
    private final BeanContext beanContext;

    CdiExpressionLanguage(BeanContainer beans, BeanContext beanContext) {
        this.beans = beans;
        this.beanContext = beanContext;
    }

    @Override
    public ELResolver resolver() {
        return new CdiELResolver(beans);
    }

    @Override
    public ExpressionFactory wrap(ExpressionFactory factory) {
        return new CdiExpressionFactory(factory, resolver(), beanContext);
    }
}
