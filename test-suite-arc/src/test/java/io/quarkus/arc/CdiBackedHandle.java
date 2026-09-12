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
package io.quarkus.arc;

import io.micronaut.cdi.runtime.CdiBean;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import org.jspecify.annotations.Nullable;

/**
 * A handle on a bean of this implementation.
 *
 * <p>A lookup by type hands out the specification's own handle, which is what this forwards to. A lookup by name
 * has no such handle to forward to — the specification resolves a name through the bean manager rather than
 * through a lookup — so for that one the bean is held directly and a reference is created from it.</p>
 *
 * @param <T> The bean type
 */
final class CdiBackedHandle<T> implements InstanceHandle<T> {

    private static final InstanceHandle<?> UNAVAILABLE = new CdiBackedHandle<>(null, null, null);

    private final @Nullable ApplicationContext context;
    private final Instance.@Nullable Handle<T> handle;
    private final @Nullable CdiBean<T> named;
    private jakarta.enterprise.context.spi.@Nullable CreationalContext<T> creation;
    private @Nullable T created;

    private CdiBackedHandle(@Nullable ApplicationContext context, Instance.@Nullable Handle<T> handle,
                            @Nullable CdiBean<T> named) {
        this.context = context;
        this.handle = handle;
        this.named = named;
    }

    CdiBackedHandle(ApplicationContext context, Instance.Handle<T> handle) {
        this(context, handle, null);
    }

    @SuppressWarnings("unchecked")
    static <T> InstanceHandle<T> unavailable() {
        return (InstanceHandle<T>) UNAVAILABLE;
    }

    static <T> InstanceHandle<T> of(ApplicationContext context, CdiBean<T> bean) {
        return new CdiBackedHandle<>(context, null, bean);
    }

    @Override
    public @Nullable T get() {
        if (handle != null) {
            return handle.get();
        }
        if (named == null) {
            // a lookup that resolved to nothing has no reference to hand out, which is the answer ArC's tests
            // read as the bean being absent
            return null;
        }
        T existing = created;
        if (existing != null) {
            return existing;
        }
        BeanManager beanManager = requireContext().getBean(BeanManager.class);
        creation = beanManager.createCreationalContext(named);
        created = named.create(creation);
        return created;
    }

    @Override
    public boolean isAvailable() {
        return handle != null || named != null;
    }

    @Override
    public InjectableBean<T> getBean() {
        if (named != null) {
            return new CdiBackedBean<>(named);
        }
        if (handle == null) {
            throw new IllegalStateException("The lookup resolved to no bean, and there is none to report");
        }
        return new CdiBackedBean<>((CdiBean<T>) handle.getBean());
    }

    @Override
    public void destroy() {
        if (handle != null) {
            handle.destroy();
            return;
        }
        T instance = created;
        if (instance != null && named != null) {
            named.destroy(instance, creation);
            created = null;
            creation = null;
        }
    }

    @Override
    public void close() {
        destroy();
    }

    private ApplicationContext requireContext() {
        ApplicationContext running = context;
        if (running == null) {
            throw new IllegalStateException("The handle has no container behind it");
        }
        return running;
    }
}
