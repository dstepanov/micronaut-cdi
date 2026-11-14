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
import jakarta.el.EvaluationListener;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.el.ELAwareBeanManager;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An expression of a factory the bean manager wrapped is evaluated in a context that reaches the container's names
 * and is otherwise the caller's: what the caller imported and the listeners it added are the evaluation's too.
 */
class CallerContextExpressionTest {

    @Named("tally")
    @Dependent
    public static class Tally {
        public int getCount() {
            return 2;
        }
    }

    @Test
    void theImportsOfTheCallersContextAreSeen() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ExpressionFactory factory = wrapped(context);
            ELContext el = new StandardELContext(factory);
            el.getImportHandler().importStatic("java.lang.Math.PI");
            assertEquals(Math.PI, factory.createValueExpression(el, "${PI}", Double.class).getValue(el));
            assertEquals(2 * Math.PI,
                factory.createValueExpression(el, "${tally.count * PI}", Double.class).getValue(el));
        }
    }

    @Test
    void theListenersOfTheCallersContextHearTheEvaluation() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ExpressionFactory factory = wrapped(context);
            ELContext el = new StandardELContext(factory);
            List<String> heard = new ArrayList<>();
            el.addEvaluationListener(new EvaluationListener() {
                @Override
                public void beforeEvaluation(ELContext context, String expression) {
                    heard.add("before " + expression);
                }

                @Override
                public void afterEvaluation(ELContext context, String expression) {
                    heard.add("after " + expression);
                }
            });
            assertEquals(Integer.valueOf(2), factory.createValueExpression(el, "${tally.count}", Integer.class).getValue(el));
            assertEquals(List.of("before ${tally.count}", "after ${tally.count}"), heard);
        }
    }

    private static ExpressionFactory wrapped(ApplicationContext context) {
        ELAwareBeanManager manager = (ELAwareBeanManager) context.getBean(BeanManager.class);
        return manager.wrapExpressionFactory(ExpressionFactory.newInstance());
    }
}
