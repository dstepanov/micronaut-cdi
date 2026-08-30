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
package io.micronaut.cdi.tck.arquillian;

import jakarta.el.ELContext;
import jakarta.enterprise.inject.spi.BeanManager;
import org.jboss.cdi.tck.spi.EL;

/**
 * The expression language of the kit's EL assertions, over the optional {@code micronaut-cdi-el} module: the
 * bean manager wraps the expression factory of the classpath, and what it evaluates sees the beans.
 */
public final class ELImpl implements EL {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T evaluateValueExpression(BeanManager beanManager, String expression, Class<T> expectedType) {
        ELContext context = createELContext(beanManager);
        return (T) factory(beanManager).createValueExpression(context, expression, expectedType)
            .getValue(context);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T evaluateMethodExpression(BeanManager beanManager, String expression, Class<T> expectedType,
                                          Class<?>[] expectedParamTypes, Object[] expectedParams) {
        ELContext context = createELContext(beanManager);
        return (T) factory(beanManager)
            .createMethodExpression(context, expression, expectedType, expectedParamTypes)
            .invoke(context, expectedParams);
    }

    @Override
    public ELContext createELContext(BeanManager beanManager) {
        return new jakarta.el.StandardELContext(factory(beanManager));
    }

    private static jakarta.el.ExpressionFactory factory(BeanManager beanManager) {
        return beanManager.wrapExpressionFactory(jakarta.el.ExpressionFactory.newInstance());
    }
}
