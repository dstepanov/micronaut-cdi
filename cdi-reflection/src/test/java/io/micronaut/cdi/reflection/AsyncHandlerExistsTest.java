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
package io.micronaut.cdi.reflection;

import jakarta.enterprise.invoke.AsyncHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a portable extension's invoker finds an async handler for a type: one registered as a service whose
 * type argument to {@code AsyncHandler.ReturnType} is the type, found through an abstract base class as well.
 */
class AsyncHandlerExistsTest {

    /**
     * The async type the registered handler handles.
     */
    public interface Eventually<T> {
    }

    /**
     * A base that implements the handler interface for its subclasses.
     */
    public abstract static class BaseHandler<T> implements AsyncHandler.ReturnType<T> {
        @Override
        public T transform(T original, Runnable completion) {
            completion.run();
            return original;
        }
    }

    /**
     * The registered handler, which implements the interface through its base.
     */
    @SuppressWarnings("rawtypes")
    public static final class EventuallyHandler extends BaseHandler<Eventually> {
    }

    @Test
    void aHandlerImplementingTheInterfaceThroughItsBaseIsFound() {
        assertTrue(PortableEvents.asyncHandlerExists(Eventually.class, getClass().getClassLoader()));
    }

    @Test
    void aTypeNoHandlerHandlesHasNone() {
        assertFalse(PortableEvents.asyncHandlerExists(Runnable.class, getClass().getClassLoader()));
    }
}
