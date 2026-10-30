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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An evaluation that fails is reported by its own failure, with what destroying the dependent beans it reached
 * threw suppressed by it, and every one of them is destroyed even where destroying others fails, two of them
 * throwing the same error included.
 */
class EvaluationCleanupFailureTest {

    /**
     * What destroying the brittle beans throws.
     */
    static final class CleanupError extends Error {
        CleanupError() {
            super("cleanup failed");
        }
    }

    /**
     * What the beans of one container share: the one error the brittle beans throw, and how often the sturdy bean
     * was destroyed.
     */
    @Singleton
    public static class Probe {
        final CleanupError error = new CleanupError();
        final AtomicInteger sturdyDestroyed = new AtomicInteger();
    }

    @Named("brittle")
    @Dependent
    public static class Brittle {
        @Inject
        Probe probe;

        @PreDestroy
        void destroyed() {
            throw probe.error;
        }

        public String getName() {
            return "brittle";
        }
    }

    @Named("chipped")
    @Dependent
    public static class Chipped {
        @Inject
        Probe probe;

        @PreDestroy
        void destroyed() {
            throw probe.error;
        }

        public String getName() {
            return "chipped";
        }
    }

    @Named("sturdy")
    @Dependent
    public static class Sturdy {
        @Inject
        Probe probe;

        @PreDestroy
        void destroyed() {
            probe.sturdyDestroyed.incrementAndGet();
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

    private static Throwable evaluate(ApplicationContext context, String expression) {
        BeanManager manager = context.getBean(BeanManager.class);
        ExpressionFactory factory = manager.wrapExpressionFactory(ExpressionFactory.newInstance());
        ELContext el = new StandardELContext(factory);
        return assertThrows(Throwable.class,
            () -> factory.createValueExpression(el, expression, String.class).getValue(el));
    }

    @Test
    void theFailureOfTheEvaluationIsReportedWithTheCleanupFailureSuppressedByIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Probe probe = context.getBean(Probe.class);
            Throwable thrown = evaluate(context, "${brittle.name}${chipped.name}${sturdy.name}${failing.boom}");
            boolean expressionFailure = false;
            for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
                expressionFailure |= "expression failed".equals(cause.getMessage());
            }
            assertTrue(expressionFailure, "the failure of the expression is lost: " + thrown);
            // the error both brittle beans threw, suppressed once
            assertEquals(List.of(probe.error), List.of(thrown.getSuppressed()));
            assertEquals(1, probe.sturdyDestroyed.get(), "the sturdy bean was not destroyed");
        }
    }

    @Test
    void anErrorTwoDestructionsThrowIsReportedOnceAndTheRestAreDestroyed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Probe probe = context.getBean(Probe.class);
            Throwable thrown = evaluate(context, "${brittle.name}${chipped.name}${sturdy.name}");
            assertSame(probe.error, thrown);
            assertEquals(0, thrown.getSuppressed().length, "the error suppresses itself");
            assertEquals(1, probe.sturdyDestroyed.get(), "the sturdy bean was not destroyed");
        }
    }
}
