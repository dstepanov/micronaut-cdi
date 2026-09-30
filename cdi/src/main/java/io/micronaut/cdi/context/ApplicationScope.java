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
package io.micronaut.cdi.context;

import io.micronaut.cdi.annotation.CdiApplicationScope;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanIdentifier;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The context of the application scope, which holds one instance of every application scoped bean for as long as
 * the application runs.
 *
 * <p>The context is active from the moment the container starts until it shuts down, and the instances in it are
 * destroyed as it shuts down, which is what the specification's application shutdown lifecycle asks for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ApplicationScope extends AbstractConcurrentCustomScope<CdiApplicationScope> {

    private final Map<BeanIdentifier, CreatedBean<?>> instances = new ConcurrentHashMap<>(32);

    private final RequestScope requestScope;

    public ApplicationScope(RequestScope requestScope) {
        // each bean is created under a lock of its own, so that a creation may wait for another thread creating
        // another bean of the scope
        super(CdiApplicationScope.class, true);
        this.requestScope = requestScope;
    }

    @Override
    protected <T> io.micronaut.context.scope.CreatedBean<T> doCreate(
        io.micronaut.context.scope.BeanCreationContext<T> creationContext) {
        // section 2.5.6: the request context is active during the @PostConstruct callback of any bean, and an
        // application scoped bean is created lazily, wherever its proxy was first reached through
        return requestScope.duringCreation(() -> super.doCreate(creationContext));
    }

    @Override
    protected Map<BeanIdentifier, CreatedBean<?>> getScopeMap(boolean forCreation) {
        if (forCreation) {
            // Contextual.create() asked for a new instance, which the scope creates and does not hold
            Map<BeanIdentifier, CreatedBean<?>> fresh = FreshInstance.take(this);
            if (fresh != null) {
                return fresh;
            }
        }
        return instances;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public void close() {
        instances.clear();
    }
}
