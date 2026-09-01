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

import io.micronaut.cdi.runtime.ObserverRegistry;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an observer observes does not change, so it is worked out once and shared by every resolution. Asking
 * twice answers the same thing, and the qualifiers handed out cannot be written to by whoever receives them.
 */
class ObserverMetadataIsStableTest {

    /**
     * Marks the event this test observes.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
    public @interface Urgent {
    }

    /**
     * The event, generic so that the observed type is more than a raw class.
     */
    public record Signal<T>(T payload) {
    }

    /**
     * Declares the observer whose metadata is inspected.
     */
    @ApplicationScoped
    public static class Listener {

        static int seen;

        void on(@Observes @Urgent Signal<String> signal) {
            seen++;
        }
    }

    @Test
    void whatAnObserverObservesIsTheSameEveryTimeItIsAsked() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObserverRegistry registry = context.getBean(ObserverRegistry.class);
            List<ObserverMethod<?>> found = registry.observers().stream()
                .filter(o -> o.getBeanClass() == Listener.class)
                .toList();
            assertEquals(1, found.size(), "expected the one observer of Listener, got " + found);
            ObserverMethod<?> observer = found.get(0);

            Type first = observer.getObservedType();
            Type second = observer.getObservedType();
            assertEquals(first, second, "the observed type should not change between calls");
            assertSame(first, second, "the observed type should be worked out once and kept");
            assertTrue(first.getTypeName().contains("Signal"),
                "the observed type should be the event it observes, got " + first);
            assertTrue(first.getTypeName().contains("String"),
                "the observed type should keep its type argument, got " + first);

            assertEquals(observer.getObservedQualifiers(), observer.getObservedQualifiers());
            assertSame(observer.getObservedQualifiers(), observer.getObservedQualifiers());
            assertEquals(1, observer.getObservedQualifiers().size(),
                "expected the one qualifier written on the parameter, got "
                    + observer.getObservedQualifiers());

            // shared now, so nobody may write to it
            assertThrows(UnsupportedOperationException.class,
                () -> observer.getObservedQualifiers().clear());
        }
    }
}
