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
import jakarta.annotation.PreDestroy;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An evaluation that fails is reported by its own failure, whatever destroying the dependent beans it reached
 * throws, and every one of them is destroyed even where destroying another fails.
 */
class EvaluationCleanupFailureTest {

    /**
     * What destroying the brittle bean throws.
     */
    static final class CleanupError extends Error {
        CleanupError() {
            super("cleanup failed");
        }
    }

    @Named("brittle")
    @Dependent
    public static class Brittle {
        @PreDestroy
        void destroyed() {
            throw new CleanupError();
        }

        public String getName() {
            return "brittle";
        }
    }

    @Named("sturdy")
    @Dependent
    public static class Sturdy {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        public String getName() {
            return "sturdy";
        }
    }

    @Named("failing")
    @Dependent
    public static class Failing {
        public String getBoom() {
            throw new IllegalArgumentException("expression failed");
        }
    }

    @Test
    void theFailureOfTheEvaluationIsReportedAndEveryBeanItReachedIsDestroyed() {
        Sturdy.DESTROYED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
            ELContext el = new StandardELContext(factory);
            Throwable thrown = assertThrows(Throwable.class, () -> factory.createValueExpression(el,
                "${brittle.name}${sturdy.name}${failing.boom}", String.class).getValue(el));
            boolean expressionFailure = false;
            for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
                expressionFailure |= "expression failed".equals(cause.getMessage());
            }
            assertTrue(expressionFailure, "the failure of the expression is lost: " + thrown);
            assertEquals(1, Sturdy.DESTROYED.get(), "the sturdy bean was not destroyed");
        }
    }
}
