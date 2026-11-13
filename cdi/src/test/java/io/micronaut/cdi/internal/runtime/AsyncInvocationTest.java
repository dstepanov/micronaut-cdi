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
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class AsyncInvocationTest {

    @Test
    void aFailingReleaseDoesNotReplaceTheResultOfTheInvocation() {
        AsyncInvocation.Completion completion = new AsyncInvocation.Completion(() -> {
            throw new IllegalStateException("a dependent failed to be destroyed");
        });
        CompletableFuture<String> original = CompletableFuture.completedFuture("done");

        CompletionStage<?> handedBack = (CompletionStage<?>) AsyncInvocation.transformReturnValue(null,
            "java.util.concurrent.CompletableFuture", original, completion);
        // the action is complete already, so the release runs as the method returns, in the invoker's finally
        assertDoesNotThrow(completion::returned);
        assertEquals("done", handedBack.toCompletableFuture().getNow(null));
    }

    @Test
    void cancellingWhatTheCallerWasHandedDoesNotKeepTheInvocationFromCompleting() {
        AtomicInteger released = new AtomicInteger();
        AsyncInvocation.Completion completion = new AsyncInvocation.Completion(released::incrementAndGet);
        CompletableFuture<String> original = new CompletableFuture<>();

        CompletionStage<?> handedBack = (CompletionStage<?>) AsyncInvocation.transformReturnValue(null,
            "java.util.concurrent.CompletableFuture", original, completion);
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

        CompletableFuture<?> handedBack = ((CompletionStage<?>) AsyncInvocation.transformReturnValue(null,
            "java.util.concurrent.CompletableFuture", original, completion)).toCompletableFuture();
        completion.returned();
        handedBack.whenComplete((value, error) -> assertEquals(1, released.get()));
        original.complete("done");

        assertEquals("done", handedBack.getNow(null));
    }

    @Test
    void aValueOfTwoAsyncTypesIsHandledAsTheTypeTheMethodWasMatchedBy() {
        AtomicInteger released = new AtomicInteger();
        AsyncInvocation.Completion completion = new AsyncInvocation.Completion(released::incrementAndGet);
        FuturePublisher original = new FuturePublisher();

        Object handedBack = AsyncInvocation.transformReturnValue(null, AsyncInvocation.FLOW_PUBLISHER, original,
            completion);
        completion.returned();

        assertTrue(handedBack instanceof Flow.Publisher<?>, "a method declared to return a publisher returns one");
        AtomicInteger received = new AtomicInteger();
        ((Flow.Publisher<String>) handedBack).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(String item) {
                received.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        assertEquals(0, released.get());
        original.publish("done");
        assertEquals(1, received.get());
        assertEquals(1, released.get(), "the publisher's completion completes the invocation");
    }

    /**
     * A value that is a future and a publisher at once, completed as a publisher.
     */
    static final class FuturePublisher extends CompletableFuture<String> implements Flow.Publisher<String> {

        private Flow.Subscriber<? super String> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super String> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
        }

        void publish(String item) {
            subscriber.onNext(item);
            subscriber.onComplete();
        }
    }
}
