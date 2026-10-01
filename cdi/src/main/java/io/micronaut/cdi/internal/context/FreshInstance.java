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
package io.micronaut.cdi.internal.context;

import io.micronaut.context.scope.CreatedBean;
import io.micronaut.context.scope.CustomScope;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Creates an instance of a bean of a scope without the scope holding it, which is what
 * {@code Contextual.create()} is: a new contextual instance each time, where reusing one is the business of the
 * context.
 *
 * <p>Micronaut creates a bean of a scope for the scope alone, handing it what creates the bean as it asks the scope
 * for the instance. So the instance is asked of the scope as usual, on a thread that has said the next instance it
 * asks that scope for is to be a new one: the scope then creates it without looking at what it holds, and keeps it
 * here rather than among its own. Everything else of the creation is what it always is - the injection, the
 * interceptors, the callbacks - and what is created is handed back with the dependents it was created with, so that
 * whoever asked can destroy it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class FreshInstance {

    private static final ThreadLocal<@Nullable FreshInstance> PENDING = new ThreadLocal<>();

    private final CustomScope<?> scope;
    private final Map<BeanIdentifier, CreatedBean<?>> created = new ConcurrentHashMap<>(2);

    private FreshInstance(CustomScope<?> scope) {
        this.scope = scope;
    }

    /**
     * Asks a scope for an instance that is to be a new one.
     *
     * @param scope  The scope the bean is of
     * @param lookup Asks the scope for the instance of the bean, the way a reference to it does
     * @param <T>    The bean type
     * @return What was created, or {@code null} where the scope is not one that creates on request, in which case
     * nothing was asked of it
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable CreatedBean<T> create(CustomScope<?> scope, Supplier<T> lookup) {
        if (!(scope instanceof ApplicationScope || scope instanceof RequestScope || scope instanceof Creating)) {
            return null;
        }
        FreshInstance fresh = new FreshInstance(scope);
        FreshInstance outer = PENDING.get();
        PENDING.set(fresh);
        T instance;
        try {
            instance = lookup.get();
        } finally {
            if (outer == null) {
                PENDING.remove();
            } else {
                PENDING.set(outer);
            }
        }
        for (CreatedBean<?> bean : fresh.created.values()) {
            if (bean.bean() == instance) {
                return (CreatedBean<T>) bean;
            }
        }
        throw new IllegalStateException("The scope " + scope.annotationType().getName()
            + " did not create the instance it was asked for");
    }

    /**
     * What a scope keeps the next instance it creates in, where the current thread asked for a new one: the
     * request is taken, so that what the creation itself asks the scope for is found where the scope holds it.
     *
     * @param scope The scope that is asked for an instance
     * @return Where the new instance is to be kept, or {@code null} where none was asked for
     */
    public static @Nullable Map<BeanIdentifier, CreatedBean<?>> take(CustomScope<?> scope) {
        FreshInstance fresh = PENDING.get();
        if (fresh == null || fresh.scope != scope) {
            return null;
        }
        PENDING.remove();
        return fresh.created;
    }

    /**
     * Marks a scope outside this package as one that {@linkplain #take(CustomScope) takes} the request for a new
     * instance as it is asked for one.
     */
    public interface Creating {
    }
}
