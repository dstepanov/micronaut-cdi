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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * A synthetic observer as the extension described it (section 2.10.5).
 *
 * @param eventType        The observed event type
 * @param qualifiers       The observed qualifiers
 * @param priority         The order among the observers of the event
 * @param async            Whether the observer is asynchronous
 * @param transactionPhase When the observer is notified
 * @param parameters       What the extension left for the observer to read
 * @param observer         The class that observes
 * @param <T>              The event type
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public record SyntheticObserverDescription<T>(
    Type eventType,
    List<Annotation> qualifiers,
    int priority,
    boolean async,
    TransactionPhase transactionPhase,
    Map<String, Object> parameters,
    Class<? extends SyntheticObserver<T>> observer
) {
}
