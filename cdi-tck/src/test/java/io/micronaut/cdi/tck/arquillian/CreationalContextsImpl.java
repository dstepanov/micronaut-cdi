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

import jakarta.enterprise.context.spi.Contextual;
import org.jboss.cdi.tck.spi.CreationalContexts;
import org.jspecify.annotations.Nullable;

/**
 * A creational context the kit can look inside: it records what was pushed and whether it was released, which is
 * what the kit's assertions about the lifecycle of one read.
 */
public final class CreationalContextsImpl implements CreationalContexts {

    @Override
    public <T> Inspectable<T> create(Contextual<T> contextual) {
        return new InspectableContext<>();
    }

    /**
     * The one creational context the kit inspects.
     *
     * @param <T> The type being created
     */
    static final class InspectableContext<T> implements Inspectable<T> {

        private @Nullable Object lastPushed;
        private boolean pushCalled;
        private boolean releaseCalled;

        @Override
        public void push(T incompleteInstance) {
            pushCalled = true;
            lastPushed = incompleteInstance;
        }

        @Override
        public void release() {
            releaseCalled = true;
        }

        @Override
        public boolean isPushCalled() {
            return pushCalled;
        }

        @Override
        public @Nullable Object getLastBeanPushed() {
            return lastPushed;
        }

        @Override
        public boolean isReleaseCalled() {
            return releaseCalled;
        }
    }
}
