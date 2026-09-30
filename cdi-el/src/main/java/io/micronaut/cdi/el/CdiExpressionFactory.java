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
import jakarta.el.ExpressionFactory;
import jakarta.el.FunctionMapper;
import jakarta.el.MethodExpression;
import jakarta.el.MethodInfo;
import jakarta.el.ValueExpression;
import jakarta.el.VariableMapper;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/**
 * The factory a wrapped factory becomes: what it creates evaluates with the beans of the container in reach.
 *
 * <p>Section 12.4 asks {@code wrapExpressionFactory} for a factory whose expressions see the beans, whichever
 * context they are later evaluated against. Creation is delegated untouched; the expressions that come back are
 * wrapped so that every context they are handed — at creation and at each evaluation — resolves the container's
 * names before anything else. An expression may be created against no context at all, and is: the context that
 * matters is the one it is evaluated against.</p>
 *
 * <p>Each call of a wrapped expression is one evaluation: a dependent bean a name resolves to is created once
 * for it, however often the expression names the bean, and destroyed when the call returns.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class CdiExpressionFactory extends ExpressionFactory {

    private final ExpressionFactory delegate;
    private final ELResolver beans;
    private final io.micronaut.context.BeanContext beanContext;

    CdiExpressionFactory(ExpressionFactory delegate, ELResolver beans,
                         io.micronaut.context.BeanContext beanContext) {
        this.delegate = delegate;
        this.beans = beans;
        this.beanContext = beanContext;
    }

    @Override
    public ValueExpression createValueExpression(@Nullable ELContext context, String expression,
                                                 Class<?> expectedType) {
        return new BeanAwareValueExpression(
            delegate.createValueExpression(reaching(context), expression, expectedType));
    }

    @Override
    public ValueExpression createValueExpression(Object instance, Class<?> expectedType) {
        return new BeanAwareValueExpression(delegate.createValueExpression(instance, expectedType));
    }

    @Override
    public MethodExpression createMethodExpression(@Nullable ELContext context, String expression,
                                                   Class<?> expectedReturnType, Class<?>[] expectedParamTypes) {
        return new BeanAwareMethodExpression(
            delegate.createMethodExpression(reaching(context), expression, expectedReturnType, expectedParamTypes));
    }

    @Override
    public <T> T coerceToType(Object obj, Class<T> targetType) {
        return delegate.coerceToType(obj, targetType);
    }

    @Override
    public ELResolver getStreamELResolver() {
        return delegate.getStreamELResolver();
    }

    @Override
    public Map<String, Method> getInitFunctionMap() {
        return delegate.getInitFunctionMap();
    }

    /**
     * The context an expression is created against: nothing is evaluated yet, so no evaluation is opened.
     */
    @Nullable
    private ELContext reaching(@Nullable ELContext context) {
        return context == null ? null : new BeanReachingContext(context, beans, beanContext, null);
    }

    /**
     * Runs one evaluation against the given context, destroying the dependent beans it reached as it completes.
     * An evaluation made from within another — an expression evaluated by a resolver of the context — belongs
     * to the outer one, which is the one that completes.
     */
    private <R> R evaluate(ELContext context, Function<ELContext, R> call) {
        if (context.getContext(CdiEvaluation.class) != null) {
            return call.apply(context);
        }
        CdiEvaluation evaluation = new CdiEvaluation();
        try {
            return call.apply(new BeanReachingContext(context, beans, beanContext, evaluation));
        } finally {
            evaluation.complete();
        }
    }

    /**
     * A context in which the container's names resolve first, and everything else is the context it wraps.
     */
    private static final class BeanReachingContext extends ELContext {

        private final ELContext wrapped;
        private final ELResolver resolver;
        private final io.micronaut.context.BeanContext beanContext;
        @Nullable
        private final CdiEvaluation evaluation;

        BeanReachingContext(ELContext wrapped, ELResolver beans,
                            io.micronaut.context.BeanContext beanContext, @Nullable CdiEvaluation evaluation) {
            this.wrapped = wrapped;
            this.beanContext = beanContext;
            this.evaluation = evaluation;
            // the container's names first, then everything Micronaut's own chain resolves — which is what
            // reaches a bean's executable methods — and finally whatever the caller's context adds
            this.resolver = new jakarta.el.CompositeELResolver();
            ((jakarta.el.CompositeELResolver) resolver).add(beans);
            ((jakarta.el.CompositeELResolver) resolver).add(io.micronaut.el.resolver.ELResolvers.standard());
            ((jakarta.el.CompositeELResolver) resolver).add(wrapped.getELResolver());
        }

        @Override
        public ELResolver getELResolver() {
            return resolver;
        }

        @Override
        public FunctionMapper getFunctionMapper() {
            return wrapped.getFunctionMapper();
        }

        @Override
        public VariableMapper getVariableMapper() {
            return wrapped.getVariableMapper();
        }

        @Override
        @org.jspecify.annotations.Nullable
        public Object getContext(Class<?> key) {
            if (key == CdiEvaluation.class) {
                // the evaluation this context was made for, which is where the resolver of the container's
                // names keeps the dependent beans it creates
                return evaluation;
            }
            Object registered = wrapped.getContext(key);
            if (registered != null) {
                return registered;
            }
            // resolving a method through the executable metadata a bean already carries needs the context that
            // compiled it, which the resolver reads from here rather than from a static holder
            if (key == io.micronaut.context.BeanDefinitionRegistry.class
                || key == io.micronaut.context.BeanContext.class) {
                return beanContext;
            }
            return null;
        }

        @Override
        public void putContext(Class<?> key, Object contextObject) {
            wrapped.putContext(key, contextObject);
        }

        @Override
        public java.util.Locale getLocale() {
            return wrapped.getLocale();
        }

        @Override
        public void setLocale(java.util.Locale locale) {
            wrapped.setLocale(locale);
        }
    }

    private final class BeanAwareValueExpression extends ValueExpression {

        private final ValueExpression wrapped;

        BeanAwareValueExpression(ValueExpression wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public Object getValue(ELContext context) {
            return evaluate(context, wrapped::getValue);
        }

        @Override
        public void setValue(ELContext context, Object value) {
            evaluate(context, reaching -> {
                wrapped.setValue(reaching, value);
                return Boolean.TRUE;
            });
        }

        @Override
        public boolean isReadOnly(ELContext context) {
            return evaluate(context, wrapped::isReadOnly);
        }

        @Override
        public Class<?> getType(ELContext context) {
            return evaluate(context, wrapped::getType);
        }

        @Override
        public Class<?> getExpectedType() {
            return wrapped.getExpectedType();
        }

        @Override
        public String getExpressionString() {
            return wrapped.getExpressionString();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof BeanAwareValueExpression other && wrapped.equals(other.wrapped);
        }

        @Override
        public int hashCode() {
            return wrapped.hashCode();
        }

        @Override
        public boolean isLiteralText() {
            return wrapped.isLiteralText();
        }
    }

    private final class BeanAwareMethodExpression extends MethodExpression {

        private final MethodExpression wrapped;

        BeanAwareMethodExpression(MethodExpression wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public MethodInfo getMethodInfo(ELContext context) {
            return evaluate(context, wrapped::getMethodInfo);
        }

        @Override
        public Object invoke(ELContext context, Object[] params) {
            return evaluate(context, reaching -> wrapped.invoke(reaching, params));
        }

        @Override
        public String getExpressionString() {
            return wrapped.getExpressionString();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof BeanAwareMethodExpression other && wrapped.equals(other.wrapped);
        }

        @Override
        public int hashCode() {
            return wrapped.hashCode();
        }

        @Override
        public boolean isLiteralText() {
            return wrapped.isLiteralText();
        }
    }
}
