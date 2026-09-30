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

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.MethodExpression;
import jakarta.el.MethodInfo;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a factory the bean manager wrapped owes an evaluation: a bean whose name is a list of identifiers is
 * found, a dependent bean is created once for the evaluation and destroyed as it completes, and an expression
 * may be created against no context.
 */
class ExpressionEvaluationTest {

    @Named("magic.golden.fish")
    @Dependent
    public static class GoldenFish {
    }

    @Named("visitor")
    @Dependent
    public static class Visitor {
        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @PostConstruct
        void created() {
            CREATED.incrementAndGet();
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        public String getName() {
            return "guest";
        }
    }

    @BeforeEach
    void reset() {
        Visitor.CREATED.set(0);
        Visitor.DESTROYED.set(0);
    }

    @Test
    void aNameThatIsAListOfIdentifiersResolvesItsBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);

            Object value = factory.createValueExpression(el, "${magic.golden.fish}", Object.class).getValue(el);

            assertInstanceOf(GoldenFish.class, value);
        }
    }

    @Test
    void aDependentBeanIsCreatedOnceForAnEvaluationAndDestroyedAsItCompletes() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);

            Object value = factory.createValueExpression(el,
                "${(visitor.name == 'guest' and visitor.name == 'guest') ? visitor.name : 'nobody'}",
                String.class).getValue(el);

            assertEquals("guest", value);
            assertEquals(1, Visitor.CREATED.get(), "one instance for every appearance of the name");
            assertEquals(1, Visitor.DESTROYED.get(), "destroyed when the evaluation completed");
        }
    }

    @Test
    void eachEvaluationHasADependentBeanOfItsOwn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            ValueExpression expression = factory.createValueExpression(el, "${visitor.name}", String.class);

            expression.getValue(el);
            expression.getValue(el);

            assertEquals(2, Visitor.CREATED.get());
            assertEquals(2, Visitor.DESTROYED.get());
        }
    }

    @Test
    void aValueExpressionIsCreatedAgainstNoContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(new ResolvingFactory());
            ValueExpression expression = factory.createValueExpression(null, "visitor", Object.class);
            assertNotNull(expression);

            Object value = expression.getValue(new StandardELContext(ExpressionFactory.newInstance()));

            assertEquals(Boolean.TRUE, value, "the two appearances of the name resolved one instance");
            assertEquals(1, Visitor.CREATED.get());
            assertEquals(1, Visitor.DESTROYED.get());
        }
    }

    @Test
    void aMethodExpressionIsCreatedAgainstNoContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(new ResolvingFactory());
            MethodExpression expression = factory.createMethodExpression(null, "visitor", Object.class, null);
            assertNotNull(expression);

            Object value = expression.invoke(new StandardELContext(ExpressionFactory.newInstance()), null);

            assertEquals(Boolean.TRUE, value, "the two appearances of the name resolved one instance");
            assertEquals(1, Visitor.CREATED.get());
            assertEquals(1, Visitor.DESTROYED.get());
        }
    }

    /**
     * Resolves the name "visitor" twice through the context it is evaluated against, and says whether both
     * appearances were one instance.
     */
    private static Object resolveTwice(ELContext context) {
        Object first = context.getELResolver().getValue(context, null, "visitor");
        Object second = context.getELResolver().getValue(context, null, "visitor");
        assertNotNull(first);
        assertSame(first, second);
        return Boolean.TRUE;
    }

    /**
     * A factory of someone else's, which knows nothing of the container: what it creates resolves names through
     * the context alone.
     */
    private static final class ResolvingFactory extends ExpressionFactory {

        @Override
        public ValueExpression createValueExpression(ELContext context, String expression, Class<?> expectedType) {
            return new ValueExpression() {
                @Override
                public Object getValue(ELContext context) {
                    return resolveTwice(context);
                }

                @Override
                public void setValue(ELContext context, Object value) {
                }

                @Override
                public boolean isReadOnly(ELContext context) {
                    return true;
                }

                @Override
                public Class<?> getType(ELContext context) {
                    return Object.class;
                }

                @Override
                public Class<?> getExpectedType() {
                    return expectedType;
                }

                @Override
                public String getExpressionString() {
                    return expression;
                }

                @Override
                public boolean equals(Object obj) {
                    return obj == this;
                }

                @Override
                public int hashCode() {
                    return System.identityHashCode(this);
                }

                @Override
                public boolean isLiteralText() {
                    return false;
                }
            };
        }

        @Override
        public ValueExpression createValueExpression(Object instance, Class<?> expectedType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MethodExpression createMethodExpression(ELContext context, String expression,
                                                       Class<?> expectedReturnType, Class<?>[] expectedParamTypes) {
            return new MethodExpression() {
                @Override
                public MethodInfo getMethodInfo(ELContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Object invoke(ELContext context, Object[] params) {
                    return resolveTwice(context);
                }

                @Override
                public String getExpressionString() {
                    return expression;
                }

                @Override
                public boolean equals(Object obj) {
                    return obj == this;
                }

                @Override
                public int hashCode() {
                    return System.identityHashCode(this);
                }

                @Override
                public boolean isLiteralText() {
                    return false;
                }
            };
        }

        @Override
        public <T> T coerceToType(Object obj, Class<T> targetType) {
            return targetType.cast(obj);
        }
    }
}
