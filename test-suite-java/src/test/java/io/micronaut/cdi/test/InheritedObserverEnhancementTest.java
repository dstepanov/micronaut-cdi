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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.SilenceInherited;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An extension that removes the annotations of a parameter of a method declared on a base class silences the
 * observer for the bean that inherits it: the removal is made through the class that declares the method, and
 * read back through the subclass that is the bean.
 */
class InheritedObserverEnhancementTest {

    /**
     * Declares the observer that is silenced.
     */
    @SilenceInherited
    public static class BaseListener {

        static boolean heard;

        void inheritedObserver(@Observes Integer event) {
            heard = true;
        }
    }

    /**
     * The bean, which inherits the observer rather than declaring it.
     */
    @ApplicationScoped
    public static class InheritingListener extends BaseListener {
    }

    @Test
    void anInheritedObserverSilencedThroughItsDeclaringClassHearsNothing() {
        BaseListener.heard = false;
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Integer.class).fire(42);
            assertFalse(BaseListener.heard,
                "the observer was silenced through the class that declares it, so the bean that inherits it "
                    + "should not have heard the event");
        }
    }
}
