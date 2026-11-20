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

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a scope a compiled bean carries is normal is read from the definitions once per container, not on every
 * question the bean container is asked about scopes, and the answers are the ones the definitions give.
 */
class RecordedScopeNormalityTest {

    /**
     * A normal scope of the application's own, which only the recorded definitions tell normal without reading the
     * annotation class.
     */
    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Lasting {
    }

    /**
     * A bean of that scope, so that the scope is recorded.
     */
    @Lasting
    public static class Durable {
    }

    @Test
    void theNormalityOfTheRecordedScopesIsReadOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Map<String, Boolean> first = container.contexts().recordedNormalities();
            assertEquals(Boolean.TRUE, first.get(Lasting.class.getName()));
            assertEquals(Boolean.TRUE, first.get(ApplicationScoped.class.getName()));
            assertEquals(Boolean.FALSE, first.get(Dependent.class.getName()));
            // asked again and again, the definitions are not walked again
            assertTrue(container.isScope(Lasting.class));
            assertTrue(container.isNormalScope(Lasting.class));
            assertFalse(container.isNormalScope(Dependent.class));
            assertSame(first, container.contexts().recordedNormalities());
        }
    }
}
