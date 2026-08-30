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
package io.micronaut.cdi.runtime;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.core.annotation.Internal;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * The built-in interceptor of section 2.9.2: the method it interposes on is one request.
 *
 * <p>{@code jakarta.enterprise.context.control.ActivateRequestContext} is an interceptor binding of the
 * specification, and this is the interceptor bound to it — a Jakarta interceptor like any the application
 * writes, at the priority the specification gives it, {@code PLATFORM_BEFORE + 100}. Being one of the chain is
 * what lets an application's own interceptors stand on either side of it: one of a lower priority runs before
 * the request begins, one of a higher priority runs within it.</p>
 *
 * <p>The request is begun before the intercepted method is called and ended when the call is over however it
 * ends, and a method called while a request is being handled already is part of that one rather than beginning
 * another. That is {@link RequestScope#call} exactly. The request ends when the method returns, so a method
 * that returns before its work is done — one that returns a reactive type, or a future — is one request only
 * up to the moment it returns.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Interceptor
@ActivateRequestContext
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
@Internal
public class ActivateRequestContextJakartaInterceptor {

    private final RequestScope requestScope;

    @Inject
    public ActivateRequestContextJakartaInterceptor(RequestScope requestScope) {
        this.requestScope = requestScope;
    }

    /**
     * Runs the intercepted method as one request.
     *
     * @param context The invocation
     * @return What the method returned
     * @throws Exception What the method threw
     */
    @AroundInvoke
    Object activate(InvocationContext context) throws Exception {
        return requestScope.call(context::proceed);
    }
}
