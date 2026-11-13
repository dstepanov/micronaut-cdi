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

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.invoke.AsyncHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The asynchronous methods of method invokers (CDI 5.0, section 10.5): the async handler a method matches, which
 * the compiler found, and the completion of one invocation, which is when the dependent instances the invocation
 * looked up are destroyed.
 *
 * <p>The built-in return type handlers for {@code CompletionStage}, {@code CompletableFuture} and
 * {@code Flow.Publisher} are implemented here. Any other handler is the service provider the compiler matched the
 * method to, loaded through the service loader by the name the compiler recorded - which is reflection, and so the
 * business of the optional module that reads classes ({@link CdiReflection}); a handler is stateless, and the
 * invoker holds the one it was built with.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class AsyncInvocation {

    /**
     * The async type of the built-in handler of {@code Flow.Publisher}, as the compiler records it.
     */
    static final String FLOW_PUBLISHER = "java.util.concurrent.Flow.Publisher";

    private static final Logger LOG = LoggerFactory.getLogger(AsyncInvocation.class);

    private AsyncInvocation() {
    }

    /**
     * The handler a record names, loaded through the service entries the class loader serves.
     *
     * @param beanContext  The context, whose class loader serves the service entries
     * @param handlerClass The class of the handler, empty for a built-in one
     * @param parameter    Whether it is a parameter type handler
     * @return The handler, or {@code null} for a built-in one
     * @throws IllegalStateException Where the handler is not registered as a service provider at runtime
     */
    static @Nullable Object handlerOf(BeanContext beanContext, String handlerClass, boolean parameter) {
        if (handlerClass.isEmpty()) {
            return null;
        }
        Class<?> kind = parameter ? AsyncHandler.ParameterType.class : AsyncHandler.ReturnType.class;
        return CdiReflection.require(beanContext, "The async handler " + handlerClass)
            .serviceProvider(kind, handlerClass, beanContext.getClassLoader());
    }

    /**
     * What a return type handler makes of the value an asynchronous method returned.
     *
     * @param handler    The handler, {@code null} for a built-in one
     * @param asyncType  The async type the compiler matched the method by, which picks the built-in handler: a
     *                   value may be of more than one async type, and the method's declared type decides
     * @param original   The value
     * @param completion What is run as the asynchronous action completes
     * @return The value handed to the caller
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static @Nullable Object transformReturnValue(@Nullable Object handler, String asyncType,
                                                 @Nullable Object original, Runnable completion) {
        if (handler instanceof AsyncHandler.ReturnType returnType) {
            return returnType.transform(original, completion);
        }
        if (handler instanceof AsyncHandler.ParameterType parameterType) {
            return parameterType.transformReturnValue(original, completion);
        }
        if (FLOW_PUBLISHER.equals(asyncType) && original instanceof Flow.Publisher<?> publisher) {
            return new CompletingPublisher<>(publisher, completion);
        }
        if (!FLOW_PUBLISHER.equals(asyncType) && original instanceof CompletionStage<?> stage) {
            return completing(stage, completion);
        }
        // nothing was returned to complete: the action is complete as the method returns
        completion.run();
        return original;
    }

    /**
     * The stage handed to the caller of an asynchronous method whose original stage completes the invocation. The
     * completion is observed on a stage of the container's own, which the caller never sees: a caller that cancels
     * or completes what it was handed cannot keep the completion from running, and so cannot keep the dependent
     * instances of the invocation alive. The stage handed back completes once the completion has run, as the
     * specification recommends, with what the original completed with.
     */
    private static CompletableFuture<Object> completing(CompletionStage<?> original, Runnable completion) {
        CompletableFuture<Object> handedBack = new CompletableFuture<>();
        original.whenComplete((value, error) -> {
            try {
                completion.run();
            } finally {
                if (error != null) {
                    handedBack.completeExceptionally(error);
                } else {
                    handedBack.complete(value);
                }
            }
        });
        return handedBack;
    }

    /**
     * What a parameter type handler makes of the argument of the async parameter.
     *
     * @param handler    The handler
     * @param original   The argument
     * @param completion What is run as the asynchronous action completes
     * @return The argument passed to the method
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static @Nullable Object transformArgument(@Nullable Object handler, @Nullable Object original,
                                              Runnable completion) {
        return handler instanceof AsyncHandler.ParameterType parameterType
            ? parameterType.transformArgument(original, completion) : original;
    }

    /**
     * The completion of one invocation: the dependent instances it looked up are destroyed once the method has
     * returned and the asynchronous action has completed, whichever comes last, and once only - a completion
     * signalled again, or after the method threw, changes nothing.
     */
    static final class Completion implements Runnable {

        private final AutoCloseable release;
        private final AtomicInteger pending = new AtomicInteger(2);
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        Completion(AutoCloseable release) {
            this.release = release;
        }

        @Override
        public void run() {
            if (completed.compareAndSet(false, true) && pending.decrementAndGet() == 0) {
                release();
            }
        }

        /**
         * The method returned normally.
         */
        void returned() {
            if (pending.decrementAndGet() == 0) {
                release();
            }
        }

        /**
         * The method threw: what it looked up goes now, and a completion signalled later is ignored.
         */
        void abandoned() {
            completed.set(true);
            release();
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                try {
                    release.close();
                } catch (Exception e) {
                    // destroying an instance may not throw (section 10.2.1): what failed is logged, and neither the
                    // value the invoker returns nor the thread that completed the action is handed the failure
                    LOG.error("Releasing what an asynchronous invocation looked up failed", e);
                }
            }
        }
    }

    /**
     * A publisher that runs the completion as its subscription terminates: completed, failed or cancelled.
     *
     * @param <T> The type of the items published
     */
    private static final class CompletingPublisher<T> implements Flow.Publisher<T> {

        private final Flow.Publisher<T> publisher;
        private final Runnable completion;

        private CompletingPublisher(Flow.Publisher<T> publisher, Runnable completion) {
            this.publisher = publisher;
            this.completion = completion;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            publisher.subscribe(new Flow.Subscriber<T>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            subscription.cancel();
                            completion.run();
                        }
                    });
                }

                @Override
                public void onNext(T item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    completion.run();
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    completion.run();
                    subscriber.onComplete();
                }
            });
        }
    }
}
