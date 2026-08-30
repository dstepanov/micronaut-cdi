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

import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.PropertyNotWritableException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * The resolver of section 12.4: a name at the base of an expression names the bean of that name.
 *
 * <p>It answers only the base of an expression — a name with nothing to the left of it — and only when a bean
 * of the container carries that name. What the name resolves to is a contextual reference to that bean, so a
 * normal-scoped bean resolves to its client proxy and the expression follows the context the way an injection
 * would. Everything after the base is the business of the resolvers of the expression language itself.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public final class CdiELResolver extends ELResolver {

    private final BeanContainer beans;

    /**
     * @param beans The container the names resolve in
     */
    public CdiELResolver(BeanContainer beans) {
        this.beans = beans;
    }

    @Override
    @Nullable
    public Object getValue(ELContext context, @Nullable Object base, Object property) {
        Bean<?> bean = beanOf(base, property);
        if (bean == null) {
            return null;
        }
        context.setPropertyResolved(base, property);
        return beans.getReference(bean, bean.getBeanClass(),
            beans.createCreationalContext(bean));
    }

    @Override
    @Nullable
    public Class<?> getType(ELContext context, @Nullable Object base, Object property) {
        Bean<?> bean = beanOf(base, property);
        if (bean == null) {
            return null;
        }
        context.setPropertyResolved(true);
        return bean.getBeanClass();
    }

    @Override
    public void setValue(ELContext context, @Nullable Object base, Object property, Object value) {
        if (beanOf(base, property) != null) {
            throw new PropertyNotWritableException("The bean " + property + " is resolved by the container and "
                + "cannot be replaced by an expression");
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, @Nullable Object base, Object property) {
        if (beanOf(base, property) == null) {
            return false;
        }
        context.setPropertyResolved(true);
        return true;
    }

    @Override
    @Nullable
    public Class<?> getCommonPropertyType(ELContext context, @Nullable Object base) {
        return base == null ? String.class : null;
    }

    @Nullable
    private Bean<?> beanOf(@Nullable Object base, Object property) {
        if (base != null || !(property instanceof String name)) {
            return null;
        }
        Set<Bean<?>> named = beans.getBeans(name);
        if (named.isEmpty()) {
            return null;
        }
        return beans.resolve(named);
    }
}
