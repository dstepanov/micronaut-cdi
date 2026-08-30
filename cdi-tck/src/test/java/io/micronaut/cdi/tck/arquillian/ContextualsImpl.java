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

import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jboss.cdi.tck.spi.Contextuals;
import org.jspecify.annotations.Nullable;

/**
 * A contextual the kit can look inside: it hands back the instance it was given and records what it was called
 * with, which is what the kit's assertions about a context read.
 */
public final class ContextualsImpl implements Contextuals {

    @Override
    public <T> Inspectable<T> create(T instance, Context context) {
        return new InspectableContextual<>(instance);
    }

    /**
     * The one contextual the kit inspects.
     *
     * @param <T> The type of the instance
     */
    static final class InspectableContextual<T> implements Inspectable<T> {

        private final T instance;
        private @Nullable CreationalContext<T> passedToCreate;
        private @Nullable T passedToDestroy;
        private @Nullable CreationalContext<T> creationalPassedToDestroy;

        InspectableContextual(T instance) {
            this.instance = instance;
        }

        @Override
        public T create(CreationalContext<T> creationalContext) {
            passedToCreate = creationalContext;
            return instance;
        }

        @Override
        public void destroy(T destroyed, CreationalContext<T> creationalContext) {
            passedToDestroy = destroyed;
            creationalPassedToDestroy = creationalContext;
            creationalContext.release();
        }

        @Override
        public @Nullable CreationalContext<T> getCreationalContextPassedToCreate() {
            return passedToCreate;
        }

        @Override
        public @Nullable T getInstancePassedToDestroy() {
            return passedToDestroy;
        }

        @Override
        public @Nullable CreationalContext<T> getCreationalContextPassedToDestroy() {
            return creationalPassedToDestroy;
        }
    }
}
