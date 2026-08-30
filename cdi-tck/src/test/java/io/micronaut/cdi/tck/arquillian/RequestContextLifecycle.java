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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.cdi.context.RequestScope;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.After;
import org.jboss.arquillian.test.spi.event.suite.Before;

/**
 * Begins a request around every test method, the way the environment the kit was written for does.
 *
 * <p>The kit's tests assume an active request context without ever starting one: in the container they were
 * written against, every test method executes inside a real request. Here the adapter is that environment, so it
 * begins the request before the method and ends it after — unless the method ended it itself, which the context
 * tests do on purpose.</p>
 */
public final class RequestContextLifecycle {

    private final ThreadLocal<Boolean> begunHere = ThreadLocal.withInitial(() -> false);

    /**
     * Begins the request the test method runs in.
     *
     * @param event The event before the method
     */
    public void beforeTest(@Observes Before event) {
        RequestScope requestScope = requestScope();
        if (requestScope != null) {
            begunHere.set(requestScope.activate());
        }
    }

    /**
     * Ends the request, where the method has not ended it itself.
     *
     * @param event The event after the method
     */
    public void afterTest(@Observes After event) {
        boolean ours = begunHere.get();
        begunHere.remove();
        RequestScope requestScope = requestScope();
        if (ours && requestScope != null && requestScope.isActive()) {
            requestScope.deactivate();
        }
    }

    private static RequestScope requestScope() {
        try {
            return CurrentDeployment.context().getBean(RequestScope.class);
        } catch (RuntimeException e) {
            // no deployment is under test, and there is no request to begin
            return null;
        }
    }
}
