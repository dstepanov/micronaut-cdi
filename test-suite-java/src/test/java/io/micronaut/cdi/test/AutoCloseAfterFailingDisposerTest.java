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
package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.AutoClose;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An auto-closeable product is closed even where its disposer method throws (CDI 5.0, section 2.10).
 */
class AutoCloseAfterFailingDisposerTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Failing {
    }

    public static class Resource implements AutoCloseable {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Dependent
    public static class ResourceFactory {
        @Produces
        @AutoClose
        @Failing
        Resource open() {
            return new Resource();
        }

        void dispose(@Disposes @Failing Resource resource) {
            throw new IllegalStateException("the disposer fails");
        }
    }

    @Test
    void theProductIsClosedWhereItsDisposerThrows() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(BeanManager.class).createInstance();
            Instance<Resource> resources = lookup.select(Resource.class, new FailingLiteral());
            Resource resource = resources.get();
            try {
                resources.destroy(resource);
            } catch (RuntimeException expected) {
                // what the disposer threw may be reported; the product is closed either way
            }
            assertTrue(resource.closed.get());
        }
    }

    static final class FailingLiteral extends jakarta.enterprise.util.AnnotationLiteral<Failing> implements Failing {
    }
}
