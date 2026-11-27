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

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An observer method that is not conditional is notified on the contextual instance of its bean (section 10.5), and
 * obtaining one where no context of the bean's normal scope is active throws (section 6.5.2) rather than the
 * observer being stepped around. A conditional observer is not notified, and throws nothing.
 */
class UnconditionalObserverOfInactiveScopeTest {

    static final AtomicInteger NOTIFIED = new AtomicInteger();

    public static class Ring {
    }

    public static class Knock {
    }

    @RequestScoped
    public static class Doorbell {
        void ring(@Observes Ring ring) {
            NOTIFIED.incrementAndGet();
        }

        void knock(@Observes(notifyObserver = Reception.IF_EXISTS) Knock knock) {
            NOTIFIED.incrementAndGet();
        }
    }

    @Test
    void anUnconditionalObserverOfAnInactiveScopeThrows() {
        NOTIFIED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertThrows(ContextNotActiveException.class,
                () -> manager.getEvent().select(Ring.class).fire(new Ring()));
            assertDoesNotThrow(() -> manager.getEvent().select(Knock.class).fire(new Knock()));
            assertEquals(0, NOTIFIED.get());
            RequestContextController controller = context.getBean(RequestContextController.class);
            controller.activate();
            try {
                manager.getEvent().select(Ring.class).fire(new Ring());
            } finally {
                controller.deactivate();
            }
            assertEquals(1, NOTIFIED.get());
        }
    }
}
