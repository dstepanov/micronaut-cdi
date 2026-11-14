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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An asynchronous observer that throws an exception lets the others be notified, and what each threw arrives
 * together (section 2.8.5). An error of the virtual machine is no exception of an observer: the notification stops
 * there, as a synchronous one does, and the completion fails with it.
 */
class AsyncObserverVirtualMachineErrorTest {

    static final List<String> NOTIFIED = new CopyOnWriteArrayList<>();

    /** The event, so that no other observer of the suite hears it. */
    public record Overflow() {
    }

    @ApplicationScoped
    public static class Observers {
        void first(@ObservesAsync @Priority(1) Overflow event) {
            NOTIFIED.add("first");
            throw new StackOverflowError("the first observer overflowed");
        }

        void second(@ObservesAsync @Priority(2) Overflow event) {
            NOTIFIED.add("second");
        }
    }

    @Test
    void anErrorOfTheVirtualMachineStopsTheNotification() {
        NOTIFIED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            CompletionException failure = assertThrows(CompletionException.class, () -> container.getEvent()
                .select(Overflow.class).fireAsync(new Overflow()).toCompletableFuture().join(), "the completion fails");
            assertInstanceOf(StackOverflowError.class, failure.getCause());
            assertEquals(List.of("first"), NOTIFIED);
        }
    }
}
