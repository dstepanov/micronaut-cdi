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
package io.micronaut.cdi.test.synthetic;

import io.micronaut.cdi.test.extension.Shift;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A reference to a bean in a normal scope is a client proxy (section 5.4): a request scoped synthetic bean injected
 * into an application scoped bean reaches the instance of whichever request is active when it is used.
 */
class NormalScopedSyntheticBeanTest {

    @Test
    @Disabled("A synthetic bean is registered at runtime as a RuntimeBeanDefinition, which Micronaut gives no scoped "
        + "proxy: the reference injected is the instance of the request active at injection. CONFORMANCE, "
        + "'Known limitations a review has named'.")
    void anInjectedRequestScopedSyntheticBeanFollowsTheActiveRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            RequestContextController requests = context.getBean(RequestContextController.class);
            Desk desk = context.getBean(Desk.class);
            int first;
            requests.activate();
            try {
                first = desk.shiftNumber();
            } finally {
                requests.deactivate();
            }
            requests.activate();
            try {
                assertNotEquals(first, desk.shiftNumber());
            } finally {
                requests.deactivate();
            }
        }
    }

    /**
     * Holds the shift for as long as the application runs.
     */
    @ApplicationScoped
    public static class Desk {

        @Inject
        Shift shift;

        /**
         * The number of the shift, read through the reference the desk was injected with: called on the desk's
         * client proxy, it reaches the desk itself, where a field read on the proxy would not.
         *
         * @return The number
         */
        public int shiftNumber() {
            return shift.number();
        }
    }
}
