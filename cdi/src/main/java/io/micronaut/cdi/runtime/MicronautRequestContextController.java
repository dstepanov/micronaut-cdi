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
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.control.RequestContextController;
import org.jspecify.annotations.Nullable;

/**
 * The built-in bean of section 2.9.1 that an application begins and ends a request with itself.
 *
 * <p>It is dependent scoped, as the specification asks, and holds only what tells it apart from another caller:
 * whether the request being handled is the one this controller began. A controller that did not begin the request
 * being handled does not end it, which is what the specification says of {@link #deactivate()}. That is per
 * thread rather than per controller, because one dependent instance injected into a bean of a wider scope is
 * reached from every thread that bean is.</p>
 *
 * <p>Beginning a request and returning, then ending it in a later call, is a shape that needs the request to be
 * held in a thread local while the caller is away, so this route needs Micronaut's propagation in its
 * thread-local mode. {@link RequestScope#run}, {@link RequestScope#supply} and {@link RequestScope#call} are the
 * forms that hold in either mode, and {@code jakarta.enterprise.context.control.ActivateRequestContext} is
 * written in terms of them.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Prototype
@Internal
public final class MicronautRequestContextController implements RequestContextController {

    private final RequestScope requestScope;
    private final ThreadLocal<@Nullable Boolean> begunHere = new ThreadLocal<>();

    public MicronautRequestContextController(RequestScope requestScope) {
        this.requestScope = requestScope;
    }

    @Override
    public boolean activate() {
        if (!requestScope.activate()) {
            return false;
        }
        begunHere.set(Boolean.TRUE);
        return true;
    }

    @Override
    public void deactivate() throws ContextNotActiveException {
        if (!requestScope.isActive()) {
            throw new ContextNotActiveException("The request scope is not active on the current thread");
        }
        if (begunHere.get() == null) {
            // the request being handled is not the one this controller began, so not this controller's to end
            return;
        }
        begunHere.remove();
        requestScope.deactivate();
    }
}
