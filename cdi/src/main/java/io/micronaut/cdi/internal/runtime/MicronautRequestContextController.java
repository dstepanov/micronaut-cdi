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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.control.RequestContextController;

/**
 * The built-in bean of section 2.9.1 that an application begins and ends a request with itself.
 *
 * <p>It is dependent scoped, as the specification asks, and holds nothing: whether the request being handled is the
 * one this controller began is said by the activation itself, which the request scope keeps in the propagated
 * context with the controller that began it and the thread it was begun on. A controller that did not begin the
 * request being handled does not end it, which is what the specification says of {@link #deactivate()}, and
 * neither does the controller that began it on a thread the propagated context was only carried to: one dependent
 * instance injected into a bean of a wider scope is reached from every thread that bean is.</p>
 *
 * <p>Beginning a request and returning, then ending it in a later call, is a shape that needs the request to stay
 * in scope while the caller is away, so this route needs Micronaut's propagation in its thread-local mode.
 * {@link RequestScope#run}, {@link RequestScope#supply} and {@link RequestScope#call} are the forms that hold in
 * either mode, and {@code jakarta.enterprise.context.control.ActivateRequestContext} is written in terms of
 * them.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Prototype
@Internal
public final class MicronautRequestContextController implements RequestContextController {

    private final RequestScope requestScope;

    public MicronautRequestContextController(RequestScope requestScope) {
        this.requestScope = requestScope;
    }

    @Override
    public boolean activate() {
        return requestScope.activate(this);
    }

    @Override
    public void deactivate() throws ContextNotActiveException {
        if (!requestScope.isActive()) {
            throw new ContextNotActiveException("The request scope is not active on the current thread");
        }
        // the request being handled is ended only where it is the one this controller began, on this thread
        requestScope.deactivate(this);
    }
}
