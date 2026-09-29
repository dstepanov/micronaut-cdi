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
package io.micronaut.cdi.test.extension;

import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The context of {@link Tended}: always active, holding one instance of every bean of the scope until it is
 * destroyed.
 */
public final class TendedContext implements AlterableContext {

    private final Map<Contextual<?>, Held<?>> held = new LinkedHashMap<>();

    @Override
    public Class<? extends Annotation> getScope() {
        return Tended.class;
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Held<T> existing = (Held<T>) held.get(contextual);
        if (existing != null) {
            return existing.instance();
        }
        T instance = contextual.create(creationalContext);
        held.put(contextual, new Held<>(contextual, instance, creationalContext));
        return instance;
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> T get(Contextual<T> contextual) {
        Held<T> existing = (Held<T>) held.get(contextual);
        return existing == null ? null : existing.instance();
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        Held<?> removed;
        synchronized (this) {
            removed = held.remove(contextual);
        }
        if (removed != null) {
            removed.destroy();
        }
    }

    /**
     * An instance the context holds, with what it was created with.
     *
     * @param contextual        The contextual that created it
     * @param instance          The instance
     * @param creationalContext The creational context it was created in
     * @param <T>               The type of the instance
     */
    private record Held<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {

        void destroy() {
            contextual.destroy(instance, creationalContext);
        }
    }
}
