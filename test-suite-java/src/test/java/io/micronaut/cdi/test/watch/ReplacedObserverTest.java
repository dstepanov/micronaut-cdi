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
package io.micronaut.cdi.test.watch;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean registered once the context runs that replaces a bean with an observer, and observes nothing itself, takes
 * the observer with it: the events fired after it is registered no longer notify the replaced bean.
 */
class ReplacedObserverTest {

    static final AtomicInteger NOTIFIED = new AtomicInteger();

    /**
     * What is fired.
     */
    public record Knock() {
    }

    /**
     * The bean with the observer, replaced once the context runs.
     */
    @Dependent
    public static class Door {
        void on(@Observes Knock knock) {
            NOTIFIED.incrementAndGet();
        }
    }

    @Test
    void aReplacementRegisteredOnceTheContextRunsTakesTheObserverWithIt() {
        NOTIFIED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Knock.class).fire(new Knock());
            assertEquals(1, NOTIFIED.get(), "the observer of the bean is notified");

            context.registerBeanDefinition(RuntimeBeanDefinition.<Door>builder(Door.class, () -> new Door() {
                    // the replacement, whose definition records no observer
                })
                .replaces(Door.class)
                .build());
            container.getEvent().select(Knock.class).fire(new Knock());
            assertEquals(1, NOTIFIED.get(), "the replaced bean's observer is notified no longer");
        }
    }
}
