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
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionLanguageTest {

    @Named("greeter")
    @ApplicationScoped
    public static class Greeter {

        public String getGreeting() {
            return "hello";
        }

        @io.micronaut.context.annotation.Executable
        public String greet(String whom) {
            return "hello " + whom;
        }
    }

    // dependent, so that no client proxy stands in front of it and makes its methods executable for its own sake
    @Named("clerk")
    @jakarta.enterprise.context.Dependent
    public static class Clerk {

        public String stamp(String what) {
            return "stamped " + what;
        }

        public String getName() {
            return "clerk";
        }

        String whisper() {
            return "unheard";
        }
    }

    @Test
    void aMethodExpressionInvokesAPublicMethodOfANamedBeanThatSaysNothingOfIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            assertEquals("stamped this", factory
                .createMethodExpression(el, "${clerk.stamp('this')}", String.class, new Class<?>[0])
                .invoke(el, new Object[0]));
            assertEquals("clerk", factory
                .createMethodExpression(el, "#{clerk.getName}", String.class, new Class<?>[0])
                .invoke(el, new Object[0]));
        }
    }

    @Test
    void onlyThePublicMethodsOfANamedBeanAreCompiledForAnExpression() {
        try (ApplicationContext context = ApplicationContext.run()) {
            java.util.Set<String> executable = new java.util.TreeSet<>();
            context.getBeanDefinition(Clerk.class).getExecutableMethods()
                .forEach(method -> executable.add(method.getMethodName()));
            assertEquals(java.util.Set.of("getName", "stamp"), executable);
        }
    }

    @Test
    void aNameInAnExpressionResolvesTheBeanOfThatName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            Object value = factory.createValueExpression(el, "${greeter.greeting}", String.class).getValue(el);
            assertEquals("hello", value);
        }
    }

    @Test
    void aMethodExpressionInvokesTheBeanOfThatName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            Object value = factory
                .createMethodExpression(el, "${greeter.greet('world')}", String.class, new Class<?>[0])
                .invoke(el, new Object[0]);
            assertEquals("hello world", value);
        }
    }

    @Test
    void theResolverAnswersTheNameOfABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ELResolver resolver = manager.getELResolver();
            assertNotNull(resolver);
            ExpressionFactory factory = ExpressionFactory.newInstance();
            ELContext el = new StandardELContext(factory);
            Object greeter = resolver.getValue(el, null, "greeter");
            assertTrue(el.isPropertyResolved());
            assertEquals(Greeter.class.getSimpleName(),
                greeter.getClass().getSuperclass() == Object.class
                    ? greeter.getClass().getSimpleName()
                    : greeter.getClass().getSuperclass().getSimpleName());
        }
    }
}
