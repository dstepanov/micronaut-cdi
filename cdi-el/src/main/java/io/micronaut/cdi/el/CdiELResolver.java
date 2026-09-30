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

import io.micronaut.cdi.runtime.CdiTypes;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.PropertyNotWritableException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The resolver of section 12.4: a name at the base of an expression names the bean of that name.
 *
 * <p>It answers only the base of an expression — a name with nothing to the left of it — and only when a bean
 * of the container carries that name. What the name resolves to is a contextual reference to that bean, so a
 * normal-scoped bean resolves to its client proxy and the expression follows the context the way an injection
 * would. Everything after the base is the business of the resolvers of the expression language itself.</p>
 *
 * <p>The name of a bean may be a list of identifiers separated by periods, which an expression reads as a
 * property of a property. The first identifiers of such a name resolve to a namespace, a value that stands for
 * nothing but the names that begin with it, and the last one resolves the bean.</p>
 *
 * <p>A bean of the dependent pseudo-scope is created for the evaluation that names it: where the context
 * carries the evaluation — the expressions of a factory the bean manager wrapped register one — the instance is
 * shared by every appearance of the name and destroyed as the evaluation completes.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public final class CdiELResolver extends ELResolver {

    private final BeanContainer beans;
    @Nullable
    private volatile Set<String> namespaces;

    /**
     * @param beans The container the names resolve in
     */
    public CdiELResolver(BeanContainer beans) {
        this.beans = beans;
    }

    @Override
    @Nullable
    public Object getValue(ELContext context, @Nullable Object base, Object property) {
        Object named = named(base, property);
        if (named == null) {
            return null;
        }
        context.setPropertyResolved(base, property);
        if (!(named instanceof Bean<?> bean)) {
            return named;
        }
        if (Dependent.class.equals(bean.getScope())
            && context.getContext(CdiEvaluation.class) instanceof CdiEvaluation evaluation) {
            return evaluation.instanceOf(bean, beans);
        }
        // Object is a bean type of every bean, while the bean class of a producer is the class declaring it
        return unproxied(beans.getReference(bean, Object.class,
            beans.createCreationalContext(bean)));
    }

    /**
     * The instance behind a client proxy, which is what an expression is evaluated against: the expression
     * language reads a type through the introspection compiled for it, and the proxy of a normal scoped bean
     * is a class of the container's own making with no introspection of its own.
     */
    private static Object unproxied(Object reference) {
        if (reference instanceof io.micronaut.aop.InterceptedProxy<?> proxy) {
            Object target = proxy.interceptedTarget();
            return target == null ? reference : target;
        }
        return reference;
    }

    @Override
    @Nullable
    public Class<?> getType(ELContext context, @Nullable Object base, Object property) {
        if (!(named(base, property) instanceof Bean<?> bean)) {
            return null;
        }
        context.setPropertyResolved(true);
        return typeOf(bean);
    }

    /**
     * The class an expression may take the bean for: the most specific class among its bean types. The bean
     * class does not answer that, since the bean class of a producer is the class that declares the producer.
     */
    private static Class<?> typeOf(Bean<?> bean) {
        List<Class<?>> classes = new ArrayList<>();
        for (Type type : bean.getTypes()) {
            Class<?> rawClass = CdiTypes.rawClassOf(type);
            if (rawClass != null && rawClass != Object.class) {
                classes.add(rawClass);
            }
        }
        for (Class<?> candidate : classes) {
            boolean mostSpecific = true;
            for (Class<?> other : classes) {
                if (!other.isAssignableFrom(candidate)) {
                    mostSpecific = false;
                    break;
                }
            }
            if (mostSpecific) {
                return candidate;
            }
        }
        // bean types narrowed to unrelated ones have nothing more specific in common
        return Object.class;
    }

    @Override
    public void setValue(ELContext context, @Nullable Object base, Object property, Object value) {
        if (named(base, property) != null) {
            throw new PropertyNotWritableException("The bean " + property + " is resolved by the container and "
                + "cannot be replaced by an expression");
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, @Nullable Object base, Object property) {
        if (named(base, property) == null) {
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

    /**
     * What a name stands for: the bean of that name, the namespace the names of other beans begin with, or
     * nothing the container knows.
     */
    @Nullable
    private Object named(@Nullable Object base, Object property) {
        if (!(property instanceof String identifier)) {
            return null;
        }
        String name;
        if (base == null) {
            name = identifier;
        } else if (base instanceof Namespace namespace) {
            name = namespace.name + '.' + identifier;
        } else {
            return null;
        }
        Set<Bean<?>> named = beans.getBeans(name);
        if (!named.isEmpty()) {
            return beans.resolve(named);
        }
        return namespaces().contains(name) ? new Namespace(name) : null;
    }

    /**
     * Every proper prefix of a bean name that ends where a period separates two identifiers. The beans of a
     * container are settled once it has started, so the names are read once.
     */
    private Set<String> namespaces() {
        Set<String> known = namespaces;
        if (known == null) {
            known = new HashSet<>();
            for (Bean<?> bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
                String name = bean.getName();
                if (name == null) {
                    continue;
                }
                for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                    known.add(name.substring(0, dot));
                }
            }
            namespaces = known;
        }
        return known;
    }

    /**
     * The first identifiers of a bean name written as a list of them: it resolves nothing itself, and the
     * identifier that follows it is looked up as the rest of the name.
     */
    private static final class Namespace {

        private final String name;

        Namespace(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "Namespace[" + name + "]";
        }
    }
}
