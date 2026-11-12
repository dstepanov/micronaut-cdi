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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncInvocationTest {

    @Test
    void cancellingWhatTheCallerWasHandedDoesNotKeepTheInvocationFromCompleting() {
        AtomicInteger released = new AtomicInteger();
        AsyncInvocation.Completion completion = new AsyncInvocation.Completion(released::incrementAndGet);
        CompletableFuture<String> original = new CompletableFuture<>();

        CompletionStage<?> handedBack = (CompletionStage<?>) AsyncInvocation.transformReturnValue(null, original,
            completion);
        completion.returned();
        assertTrue(handedBack.toCompletableFuture().cancel(true));
        original.complete("done");

        assertEquals(1, released.get(), "the dependents of the invocation are released as the original completes");
    }

    @Test
    void theStageHandedBackCompletesWithTheOriginalOnceTheInvocationIsReleased() {
        AtomicInteger released = new AtomicInteger();
        AsyncInvocation.Completion completion = new AsyncInvocation.Completion(released::incrementAndGet);
        CompletableFuture<String> original = new CompletableFuture<>();

        CompletableFuture<?> handedBack = ((CompletionStage<?>) AsyncInvocation.transformReturnValue(null, original,
            completion)).toCompletableFuture();
        completion.returned();
        handedBack.whenComplete((value, error) -> assertEquals(1, released.get()));
        original.complete("done");

        assertEquals("done", handedBack.getNow(null));
    }
}
