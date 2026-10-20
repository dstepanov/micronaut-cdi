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
package io.micronaut.cdi.test;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A definition the container cannot read for a reason other than a class missing from the classpath - a condition
 * that fails, say - is a broken deployment, and the container says so with the cause rather than leaving the bean
 * out as though it were not there. A definition whose condition does not hold is left out, as Micronaut leaves it.
 */
class BrokenDefinitionReportsItsCauseTest {

    static final AtomicBoolean BROKEN = new AtomicBoolean();
    static final AtomicBoolean DISABLED = new AtomicBoolean();

    /**
     * Fails while the test arms it, and holds or not as the test says otherwise.
     */
    public static final class Failing implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            if (BROKEN.get()) {
                throw new IllegalStateException("the condition of Fragile cannot be evaluated");
            }
            return !DISABLED.get();
        }
    }

    @Dependent
    @Requires(condition = Failing.class)
    public static class Fragile {
    }

    @Test
    void aConditionThatFailsIsReportedWithItsCause() {
        // the condition fails as the beans of the container are read, which the bean context does once
        BROKEN.set(true);
        try {
            Throwable failure = assertThrows(Throwable.class, () -> {
                try (ApplicationContext context = ApplicationContext.run()) {
                    context.getBean(CdiBeanContainer.class).getBeans(Object.class, Any.Literal.INSTANCE);
                }
            });
            assertTrue(causedBy(failure, "the condition of Fragile cannot be evaluated"),
                () -> "the cause is not among " + failure);
        } finally {
            BROKEN.set(false);
        }
    }

    @Test
    void aDefinitionWhoseConditionDoesNotHoldIsLeftOut() {
        DISABLED.set(true);
        try (ApplicationContext context = ApplicationContext.run()) {
            assertFalse(context.getBean(MicronautBeanContainer.class).getBeans(Object.class, Any.Literal.INSTANCE)
                .stream().anyMatch(bean -> bean.getBeanClass() == Fragile.class));
        } finally {
            DISABLED.set(false);
        }
    }

    private static boolean causedBy(Throwable failure, String message) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (message.equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }
}
