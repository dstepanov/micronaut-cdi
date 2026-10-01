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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.*;

class ProviderInjectionPointTest {
    @Test
    void deferredProvidersKeepEachRequestingInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            FieldConsumer field = context.getBean(FieldConsumer.class);
            ConstructorConsumer constructor = context.getBean(ConstructorConsumer.class);
            InitializerConsumer initializer = context.getBean(InitializerConsumer.class);
            assertPoint(field.payload.get(), FieldConsumer.class, "payload", "field");
            assertPoint(constructor.payload.get(), ConstructorConsumer.class, ConstructorConsumer.class.getName(), "constructor");
            assertPoint(initializer.payload.get(), InitializerConsumer.class, "initialize", "initializer");
            // Alternate calls after construction: neither a discarded resolution path nor the most recent
            // injection is the metadata of these two independent providers.
            assertPoint(field.payload.get(), FieldConsumer.class, "payload", "field");
            assertPoint(constructor.payload.get(), ConstructorConsumer.class, ConstructorConsumer.class.getName(), "constructor");
            assertNull(context.getBean(DirectLookup.class).injectionPoint(),
                "a direct lookup after Provider.get() has no requesting injection point");
        }
    }

    @Test
    void aFailingDeferredCreationDoesNotLeaveItsInjectionPointBehind() {
        try (ApplicationContext context = ApplicationContext.run()) {
            FailingConsumer consumer = context.getBean(FailingConsumer.class);
            assertThrows(IllegalStateException.class, consumer.payload::get);
            assertNull(context.getBean(DirectLookup.class).injectionPoint());
            assertPoint(context.getBean(FieldConsumer.class).payload.get(), FieldConsumer.class, "payload", "field");
        }
    }

    private static void assertPoint(Payload payload, Class<?> owner, String member, String key) {
        InjectionPoint point = payload.injectionPoint();
        assertNotNull(point, "the deferred producer receives its requesting injection point");
        assertEquals(owner, point.getBean().getBeanClass());
        assertEquals(member, point.getMember().getName());
        assertEquals(key, point.getQualifiers().stream().filter(Key.class::isInstance)
            .map(Key.class::cast).findFirst().orElseThrow().value());
        assertEquals(Payload.class, point.getType(), "the lookup describes the selected bean type");
    }

    @Qualifier
    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @interface Key { @Nonbinding String value() default ""; }

    record Payload(InjectionPoint injectionPoint) { }
    record DirectLookup(InjectionPoint injectionPoint) { }

    @Dependent
    static class Producer {
        @Produces @Key
        Payload payload(@io.micronaut.core.annotation.Nullable InjectionPoint point) {
            if (point != null && point.getQualifiers().stream().filter(Key.class::isInstance)
                .map(Key.class::cast).anyMatch(key -> key.value().equals("fail"))) {
                throw new IllegalStateException("failed deferred creation");
            }
            return new Payload(point);
        }

        @Produces
        DirectLookup direct(@io.micronaut.core.annotation.Nullable InjectionPoint point) { return new DirectLookup(point); }
    }

    @Dependent
    static class FieldConsumer {
        @Inject @Key("field") Provider<Payload> payload;
    }

    @Dependent
    static class ConstructorConsumer {
        final Provider<Payload> payload;
        @Inject ConstructorConsumer(@Key("constructor") Provider<Payload> payload) { this.payload = payload; }
    }

    @Dependent
    static class InitializerConsumer {
        Provider<Payload> payload;
        @Inject void initialize(@Key("initializer") Provider<Payload> payload) { this.payload = payload; }
    }

    @Dependent
    static class FailingConsumer {
        @Inject @Key("fail") Provider<Payload> payload;
    }
}
