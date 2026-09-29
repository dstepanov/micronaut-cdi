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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The creation of a bean of a normal scope may wait for another thread that creates another bean of the same scope.
 *
 * <p>A scope creates each bean under a lock of the bean's own rather than one of the whole scope. Under a lock of
 * the whole scope the other thread could not create its bean until the first creation had finished, which is waiting
 * for it: the {@code @PostConstruct} below timed out.</p>
 */
class CrossThreadCreationTest {

    @Test
    void aCreationMayWaitForAnotherThreadCreatingAnotherBeanOfTheScope() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("helper", context.getBean(Waiter.class).seen());
        }
    }

    /**
     * Waits, while it is being created, for another thread to reach a bean of the same scope.
     */
    @ApplicationScoped
    public static class Waiter {

        @Inject
        Helper helper;

        private String seen;

        @PostConstruct
        void init() throws Exception {
            seen = CompletableFuture.supplyAsync(() -> helper.name()).get(5, TimeUnit.SECONDS);
        }

        public String seen() {
            return seen;
        }
    }

    /**
     * Created on the other thread, the first time it is reached through its client proxy.
     */
    @ApplicationScoped
    public static class Helper {

        public String name() {
            return "helper";
        }
    }
}
